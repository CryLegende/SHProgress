package org.Cry_Legende.sHProgress.network;

import org.Cry_Legende.sHProgress.network.ProgressDatabase.Gain;
import org.Cry_Legende.sHProgress.data.PlayerDataManager.ImportListener;
import org.Cry_Legende.sHProgress.network.ProgressDatabase.ImportResult;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.sql.SQLException;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.logging.Level;

/**
 * Progression des joueurs partagée entre les serveurs Opale. Les compteurs vivent en mémoire pendant la partie
 * (PlayerDataManager) ; ici se trouvent leur lecture en base à l'arrivée du joueur, l'ajout des gains par
 * incrément atomique à intervalle régulier et à son départ, et la réservation en base de chaque récompense.
 * Chargée seulement avec SHNetwork.
 *
 * <p>Aucune méthode ne touche la base sur le fil principal ni sur le fil du bus.</p>
 */
public final class NetworkProgress {

    private static final int ATTEMPTS = 3;
    private static final long RETRY_TICKS = 100L;
    private static final int IMPORT_CHUNK = 500;

    /** Gains d'un joueur pas encore écrits en base : progrès -> (somme ajoutée, plafond). */
    private static final class Pending {
        private final Map<String, int[]> gains = new HashMap<>();

        synchronized void add(String advancement, int amount, int cap) {
            int[] gain = gains.computeIfAbsent(advancement, k -> new int[]{0, cap});
            gain[0] += amount;
            gain[1] = cap;
        }

        synchronized Map<String, Gain> drain() {
            Map<String, Gain> copy = new HashMap<>();
            gains.forEach((id, gain) -> {
                if (gain[0] != 0) {
                    copy.put(id, new Gain(gain[0], gain[1]));
                }
            });
            gains.clear();
            return copy;
        }

        synchronized void restore(Map<String, Gain> back) {
            back.forEach((id, gain) -> add(id, gain.amount(), gain.cap()));
        }
    }

    private final JavaPlugin plugin;
    private final NetBus bus;
    private final ProgressDatabase db;
    private final Map<UUID, Pending> pending = new ConcurrentHashMap<>();
    /** Écriture de départ en cours pour chaque joueur qui vient de quitter ce serveur. */
    private final Map<UUID, CompletableFuture<Void>> departures = new ConcurrentHashMap<>();

    public NetworkProgress(JavaPlugin plugin, NetBus bus, ProgressDatabase db) {
        this.plugin = plugin;
        this.bus = bus;
        this.db = db;
    }

    /**
     * Inscrit l'écriture de départ auprès de SHNetwork et lance l'écriture régulière des gains.
     *
     * @param flushTicks intervalle entre deux écritures, en ticks
     */
    public void start(long flushTicks) {
        bus.registerSaveTask(player -> depart(player.getUniqueId()));
        plugin.getServer().getScheduler().runTaskTimerAsynchronously(plugin, this::flushPending, flushTicks, flushTicks);
    }

    /** Fil d'écriture : le planificateur asynchrone de Bukkit, ou le fil courant quand le plugin s'arrête. */
    private void io(Runnable task) {
        if (plugin.isEnabled()) {
            try {
                plugin.getServer().getScheduler().runTaskAsynchronously(plugin, task);
                return;
            } catch (RuntimeException ignored) {
                // plugin en cours de désactivation : la tâche tourne ici
            }
        }
        task.run();
    }

    private void later(Runnable task) {
        if (plugin.isEnabled()) {
            try {
                plugin.getServer().getScheduler().runTaskLaterAsynchronously(plugin, task, RETRY_TICKS);
                return;
            } catch (RuntimeException ignored) {
                // plugin en cours de désactivation : plus de nouvelle tentative
            }
        }
        plugin.getLogger().severe("Base de la progression injoignable pendant l'arrêt du plugin, une opération est abandonnée.");
    }

    // ------------------------------------------------------------------ lecture à l'arrivée

    /**
     * Lit la progression du joueur en base une fois que le serveur qu'il quitte a fini d'écrire la sienne, puis la
     * remet sur le fil principal.
     *
     * @param wait    attente maximale du serveur de départ
     * @param applied reçoit la progression lue, sur le fil principal
     */
    public void load(UUID uuid, Duration wait, Consumer<Map<String, Integer>> applied) {
        // un départ de ce serveur encore en cours d'écriture (reconnexion rapide) passe avant la lecture
        CompletableFuture<Void> previous = departures.remove(uuid);
        CompletableFuture<Boolean> barrier = bus.awaitSaved(uuid, wait);
        CompletableFuture<Boolean> ready = previous == null ? barrier
            : barrier.thenCombine(previous.handle((v, e) -> null), (saved, ignored) -> saved);
        ready.whenComplete((saved, error) -> {
            if (error != null || !Boolean.TRUE.equals(saved)) {
                plugin.getLogger().warning("Départ de " + uuid + " non confirmé par l'autre serveur, progression lue telle qu'elle est en base.");
            }
            io(() -> read(uuid, applied, 1));
        });
    }

    private void read(UUID uuid, Consumer<Map<String, Integer>> applied, int attempt) {
        try {
            Map<String, Integer> loaded = db.load(uuid);
            bus.sync(() -> applied.accept(loaded));
        } catch (SQLException e) {
            if (attempt < ATTEMPTS) {
                plugin.getLogger().log(Level.WARNING, "Lecture de la progression de " + uuid + " impossible (essai " + attempt + "), nouvel essai : " + e.getMessage());
                later(() -> read(uuid, applied, attempt + 1));
            } else {
                plugin.getLogger().log(Level.SEVERE, "Lecture de la progression de " + uuid + " impossible, rien ne compte pour ce joueur jusqu'à sa prochaine connexion", e);
            }
        }
    }

    // ------------------------------------------------------------------ écriture des gains

    /** Note un gain, écrit en base au prochain passage de l'écriture régulière ou au départ du joueur. */
    public void recordGain(UUID uuid, String advancement, int amount, int cap) {
        if (amount != 0) {
            pending.computeIfAbsent(uuid, k -> new Pending()).add(advancement, amount, cap);
        }
    }

    /**
     * Écrit en base ce qui reste à écrire pour le joueur qui part. Appelé par SHNetwork pendant PlayerQuitEvent et
     * par le plugin lui-même : le second appel rend la même écriture.
     */
    public CompletableFuture<Void> depart(UUID uuid) {
        CompletableFuture<Void> future = departures.compute(uuid, (id, running) -> running != null ? running : startDeparture(id));
        future.whenComplete((v, e) -> departures.remove(uuid, future));
        return future;
    }

    private CompletableFuture<Void> startDeparture(UUID uuid) {
        Pending left = pending.remove(uuid);
        Map<String, Gain> gains = left == null ? Map.of() : left.drain();
        if (gains.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        CompletableFuture<Void> future = new CompletableFuture<>();
        io(() -> {
            try {
                write(uuid, gains);
                future.complete(null);
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Progression de " + uuid + " non écrite au départ, gains perdus : " + gains, e);
                future.completeExceptionally(e);
            }
        });
        return future;
    }

    /** Écrit les gains avec quelques essais rapprochés. */
    private void write(UUID uuid, Map<String, Gain> gains) throws SQLException {
        SQLException last = null;
        for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
            try {
                db.add(uuid, gains);
                return;
            } catch (SQLException e) {
                last = e;
                if (attempt < ATTEMPTS) {
                    try {
                        Thread.sleep(500L);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }
        throw last;
    }

    /** Écriture régulière, sur un fil asynchrone. Un échec remet les gains en attente pour le prochain passage. */
    public void flushPending() {
        for (Map.Entry<UUID, Pending> entry : pending.entrySet()) {
            Map<String, Gain> gains = entry.getValue().drain();
            if (gains.isEmpty()) {
                continue;
            }
            try {
                db.add(entry.getKey(), gains);
            } catch (SQLException e) {
                pending.computeIfAbsent(entry.getKey(), k -> new Pending()).restore(gains);
                plugin.getLogger().log(Level.WARNING, "Écriture de la progression de " + entry.getKey() + " impossible, nouvel essai au prochain passage : " + e.getMessage());
            }
        }
    }

    /** Arrêt du plugin : écrit tout ce qui reste, en attendant la fin. */
    public void flushAll() {
        for (UUID uuid : pending.keySet()) {
            Pending left = pending.remove(uuid);
            Map<String, Gain> gains = left == null ? Map.of() : left.drain();
            if (gains.isEmpty()) {
                continue;
            }
            try {
                write(uuid, gains);
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Progression de " + uuid + " non écrite à l'arrêt, gains perdus : " + gains, e);
            }
        }
    }

    // ------------------------------------------------------------------ récompenses

    /**
     * Réserve la récompense du progrès pour le joueur, puis la donne sur le fil principal si ce serveur est le
     * premier à la réserver. Une base injoignable après plusieurs essais ne donne rien : jamais deux fois.
     */
    public void claimReward(UUID uuid, String advancement, String player, Runnable payout) {
        io(() -> claim(uuid, advancement, player, payout, 1));
    }

    private void claim(UUID uuid, String advancement, String player, Runnable payout, int attempt) {
        try {
            if (db.claimReward(uuid, advancement, System.currentTimeMillis())) {
                bus.sync(payout);
            }
        } catch (SQLException e) {
            if (attempt < ATTEMPTS) {
                later(() -> claim(uuid, advancement, player, payout, attempt + 1));
            } else {
                plugin.getLogger().log(Level.SEVERE, "Récompense du progrès " + advancement + " non donnée à " + player + " : base injoignable", e);
            }
        }
    }

    // ------------------------------------------------------------------ importation

    /**
     * Copie les fichiers playerdata/ dans la base, sur un fil asynchrone ; le résultat revient sur le fil principal.
     *
     * @param completed vrai si la valeur termine le progrès (identifiant, valeur)
     */
    public void importFiles(File folder, BiPredicate<String, Integer> completed, ImportListener listener) {
        io(() -> {
            try {
                ImportResult total = readAndImport(folder, completed);
                bus.sync(() -> listener.done(total.players(), total.rows(), total.rewards()));
            } catch (SQLException | RuntimeException e) {
                plugin.getLogger().log(Level.SEVERE, "Importation de la progression interrompue", e);
                bus.sync(() -> listener.failed(String.valueOf(e.getMessage())));
            }
        });
    }

    private ImportResult readAndImport(File folder, BiPredicate<String, Integer> completed) throws SQLException {
        File[] files = folder.listFiles((dir, name) -> name.endsWith(".yml"));
        int players = 0;
        int rows = 0;
        int rewards = 0;
        if (files == null) {
            return new ImportResult(0, 0, 0);
        }
        Map<UUID, Map<String, Integer>> chunk = new HashMap<>();
        for (int i = 0; i < files.length; i++) {
            UUID uuid;
            try {
                uuid = UUID.fromString(files[i].getName().replace(".yml", ""));
            } catch (IllegalArgumentException notAPlayer) {
                continue;
            }
            ConfigurationSection section = YamlConfiguration.loadConfiguration(files[i]).getConfigurationSection("progress");
            if (section != null) {
                Map<String, Integer> progress = new HashMap<>();
                for (String key : section.getKeys(false)) {
                    progress.put(key, section.getInt(key));
                }
                chunk.put(uuid, progress);
            }
            if (chunk.size() >= IMPORT_CHUNK || i == files.length - 1) {
                ImportResult part = db.importProgress(chunk, completed, System.currentTimeMillis());
                players += part.players();
                rows += part.rows();
                rewards += part.rewards();
                chunk.clear();
            }
        }
        return new ImportResult(players, rows, rewards);
    }

    public void close() {
        db.close();
    }
}
