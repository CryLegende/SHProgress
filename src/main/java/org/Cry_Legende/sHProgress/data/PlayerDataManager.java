package org.Cry_Legende.sHProgress.data;

import com.fren_gor.ultimateAdvancementAPI.advancement.Advancement;
import org.Cry_Legende.sHProgress.SHProgress;
import org.Cry_Legende.sHProgress.advancement.ConfiguredAdvancement;
import org.Cry_Legende.sHProgress.network.NetworkProgress;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class PlayerDataManager {

    /** Suite d'une importation vers la base du réseau, rappelée sur le fil principal. */
    public interface ImportListener {

        void done(int players, int rows, int rewards);

        void failed(String error);
    }

    private final SHProgress plugin;
    private final File dataFolder;
    // Cache des joueurs en ligne ; maps concurrentes car la sauvegarde automatique est asynchrone
    private final Map<UUID, Map<String, Integer>> playerProgressCache = new ConcurrentHashMap<>();
    private final Set<UUID> dirty = ConcurrentHashMap.newKeySet();
    // La sauvegarde auto (asynchrone) et celle de la déconnexion ne doivent jamais écrire le même fichier en même temps
    private final Object saveLock = new Object();

    /**
     * Progressions pas encore montrées au client, par joueur. UltimateAdvancementAPI renvoie tout l'onglet (~120 Kio
     * envoyés, un critère par unité) à chaque mise à jour : on n'affiche donc une progression non terminée qu'au plus
     * toutes les {@link #VISUAL_FLUSH_TICKS} ticks. Une progression terminée part tout de suite (toast, récompenses).
     */
    private final Map<UUID, Map<String, Integer>> pendingVisual = new ConcurrentHashMap<>();
    private static final long VISUAL_FLUSH_TICKS = 20L * 10;

    // Non nul seulement avec SHNetwork : progression en base partagée, lue à l'arrivée du joueur
    private NetworkProgress net;

    public PlayerDataManager(SHProgress plugin) {
        this.plugin = plugin;
        this.dataFolder = new File(plugin.getDataFolder(), "playerdata");
        if (!dataFolder.exists()) {
            dataFolder.mkdirs();
        }
        startAutoSaveTask();
        startVisualFlushTask();
    }

    // --- LOGIQUE DE CONNEXION / DÉCONNEXION ---

    /** Passe en mode réseau : la progression vient de la base, plus des fichiers. */
    public void enableNetwork(NetworkProgress network) {
        this.net = network;
    }

    /** Connexion d'un joueur : son fichier est lu tout de suite, ou sa progression en base après la barrière de passage. */
    public void onJoin(UUID uuid) {
        if (net == null) {
            loadPlayerData(uuid);
            return;
        }
        Duration wait = Duration.ofSeconds(Math.max(0, plugin.getConfig().getInt("reseau.attente-depart-secondes", 30)));
        net.load(uuid, wait, loaded -> applyLoaded(uuid, loaded));
    }

    /** Progression lue en base (fil principal) : mise en mémoire et poussée vers le client si le joueur est toujours là. */
    private void applyLoaded(UUID uuid, Map<String, Integer> loaded) {
        Player player = plugin.getServer().getPlayer(uuid);
        if (player == null) {
            return; // parti pendant la lecture
        }
        playerProgressCache.put(uuid, new ConcurrentHashMap<>(loaded));
        plugin.getAdvancementManager().syncAwaiting(player);
    }

    public boolean isLoaded(UUID uuid) {
        return playerProgressCache.containsKey(uuid);
    }

    public void loadPlayerData(UUID uuid) {
        File playerFile = new File(dataFolder, uuid.toString() + ".yml");
        Map<String, Integer> progressMap = new ConcurrentHashMap<>();

        if (playerFile.exists()) {
            FileConfiguration config = YamlConfiguration.loadConfiguration(playerFile);
            ConfigurationSection progressSection = config.getConfigurationSection("progress");
            if (progressSection != null) {
                for (String key : progressSection.getKeys(false)) {
                    progressMap.put(key, progressSection.getInt(key));
                }
            }
        }
        playerProgressCache.put(uuid, progressMap);
    }

    /**
     * Charge le fichier du joueur s'il n'est pas en mémoire. Avec SHNetwork, la lecture en base se fait à part
     * (jamais de SQL sur le fil principal) : rien n'est chargé ici et la réponse est fausse tant qu'elle n'est pas finie.
     *
     * @return vrai si la progression du joueur est en mémoire
     */
    public boolean loadIfAbsent(UUID uuid) {
        if (playerProgressCache.containsKey(uuid)) {
            return true;
        }
        if (net != null) {
            return false;
        }
        loadPlayerData(uuid);
        return true;
    }

    public void savePlayerData(UUID uuid) {
        Map<String, Integer> progressMap = playerProgressCache.get(uuid);
        if (progressMap == null) {
            return; // Pas de données à sauvegarder
        }
        synchronized (saveLock) {
            dirty.remove(uuid);

            File playerFile = new File(dataFolder, uuid.toString() + ".yml");
            FileConfiguration config = new YamlConfiguration();
            config.set("progress", new HashMap<>(progressMap));

            try {
                config.save(playerFile);
            } catch (IOException e) {
                plugin.getLogger().severe("Impossible de sauvegarder les données du joueur " + uuid + " : " + e.getMessage());
            }
        }
    }

    public void unloadPlayerData(UUID uuid) {
        // Rien à montrer à un joueur parti : la progression enregistrée est repoussée à sa prochaine connexion
        pendingVisual.remove(uuid);
        plugin.getAdvancementManager().forgetAwaiting(uuid);
        if (net != null) {
            // les gains partent en base ; SHNetwork attend cette écriture avant de laisser le joueur arriver ailleurs
            net.depart(uuid);
            playerProgressCache.remove(uuid);
            return;
        }
        if (playerProgressCache.containsKey(uuid)) {
            savePlayerData(uuid);
            playerProgressCache.remove(uuid);
        }
    }

    public void saveAll() {
        if (net != null) {
            net.flushAll();
            return;
        }
        playerProgressCache.keySet().forEach(this::savePlayerData);
    }

    /**
     * Récompense d'un progrès terminé. Sans SHNetwork elle est donnée tout de suite ; avec lui, le serveur qui
     * réserve la récompense en base la donne, les autres non.
     */
    public void giveReward(Player player, String advancementId, Runnable payout) {
        if (net == null) {
            payout.run();
            return;
        }
        net.claimReward(player.getUniqueId(), advancementId, player.getName(), payout);
    }

    /**
     * Copie les fichiers playerdata/ dans la base du réseau (sans toucher aux progressions déjà présentes).
     *
     * @return faux si le mode réseau n'est pas actif
     */
    public boolean importToNetwork(ImportListener listener) {
        if (net == null) {
            return false;
        }
        net.importFiles(dataFolder, (id, value) -> {
            ConfiguredAdvancement conf = plugin.getAdvancementManager().getConfigured(id);
            return conf != null && value >= conf.getAmount();
        }, listener);
        return true;
    }

    // --- GESTION DE LA PROGRESSION ---

    public int getProgression(UUID uuid, String advancementId) {
        Map<String, Integer> progress = playerProgressCache.get(uuid);
        return progress == null ? 0 : progress.getOrDefault(advancementId, 0);
    }

    public boolean isCompleted(UUID uuid, ConfiguredAdvancement advConfig) {
        return getProgression(uuid, advConfig.getFullId()) >= advConfig.getAmount();
    }

    public void setProgression(UUID uuid, String advancementId, int newProgress) {
        // Jamais de carte vide à la place du fichier (joueur déjà connecté lors d'un rechargement du plugin)
        if (!loadIfAbsent(uuid)) {
            return;
        }
        playerProgressCache.get(uuid).put(advancementId, newProgress);
        if (net == null) {
            dirty.add(uuid);
        }

        // Mise à jour visuelle via l'API (toast et récompenses si le progrès est terminé)
        Advancement advancement = plugin.getAdvancementManager().getAdvancement(advancementId);
        Player player = plugin.getServer().getPlayer(uuid);
        if (advancement != null && player != null) {
            ConfiguredAdvancement conf = plugin.getAdvancementManager().getConfigured(advancementId);
            boolean completed = conf == null || newProgress >= conf.getAmount();
            if (completed) {
                Map<String, Integer> pending = pendingVisual.get(uuid);
                if (pending != null) {
                    pending.remove(advancementId);
                }
                advancement.setProgression(player, newProgress);
            } else {
                pendingVisual.computeIfAbsent(uuid, k -> new ConcurrentHashMap<>()).put(advancementId, newProgress);
            }
        }
    }

    /**
     * Montre au client les progressions en attente. Sur le thread principal (appels UltimateAdvancementAPI) ; les
     * progressions d'un même passage partent dans un seul paquet (UltimateAdvancementAPI les regroupe par tick).
     */
    private void startVisualFlushTask() {
        new BukkitRunnable() {
            @Override
            public void run() {
                for (UUID uuid : pendingVisual.keySet()) {
                    Map<String, Integer> pending = pendingVisual.remove(uuid);
                    Player player = plugin.getServer().getPlayer(uuid);
                    if (pending == null || player == null) {
                        continue;
                    }
                    for (Map.Entry<String, Integer> entry : pending.entrySet()) {
                        Advancement advancement = plugin.getAdvancementManager().getAdvancement(entry.getKey());
                        if (advancement != null) {
                            advancement.setProgression(player, entry.getValue());
                        }
                    }
                }
            }
        }.runTaskTimer(plugin, VISUAL_FLUSH_TICKS, VISUAL_FLUSH_TICKS);
    }

    /**
     * Comme CustomAdvancements : rien ne compte tant que le progrès parent n'est pas terminé.
     */
    public void incrementProgression(Player player, ConfiguredAdvancement advConfig, int amount) {
        UUID uuid = player.getUniqueId();
        if (!loadIfAbsent(uuid)) {
            return; // progression pas encore lue en base : rien ne compte avant son arrivée
        }
        if (!"root".equals(advConfig.getParentId())) {
            ConfiguredAdvancement parent = plugin.getAdvancementManager().getConfigured(advConfig.getTabId() + "/" + advConfig.getParentId());
            if (parent == null || !isCompleted(uuid, parent)) {
                return;
            }
        }

        String fullId = advConfig.getFullId();
        int currentProgress = getProgression(uuid, fullId);
        int maxProgress = advConfig.getAmount();
        if (currentProgress >= maxProgress) {
            return; // Déjà complété
        }

        int next = Math.min(currentProgress + amount, maxProgress);
        if (net != null) {
            net.recordGain(uuid, fullId, next - currentProgress, maxProgress);
        }
        setProgression(uuid, fullId, next);
    }

    // --- MIGRATION DEPUIS CUSTOMADVANCEMENTS ---

    /**
     * Convertit plugins/CustomAdvancements/data/&lt;uuid&gt;.yml (arbre.progrès.progress/completed)
     * en playerdata/&lt;uuid&gt;.yml (progress: {arbre/progrès: n}). Une seule fois, sans écraser de fichier existant.
     */
    public void migrateFromCustomAdvancements() {
        File marker = new File(plugin.getDataFolder(), "migration-customadvancements.txt");
        File legacyFolder = new File(plugin.getDataFolder().getParentFile(), "CustomAdvancements/data");
        File[] legacyFiles = legacyFolder.listFiles((dir, name) -> name.endsWith(".yml"));
        if (marker.exists() || legacyFiles == null || legacyFiles.length == 0) {
            return;
        }
        plugin.getLogger().info("Migration de " + legacyFiles.length + " fichiers CustomAdvancements...");
        int migrated = 0, skipped = 0;
        for (File legacy : legacyFiles) {
            File target = new File(dataFolder, legacy.getName());
            if (target.exists()) {
                skipped++;
                continue;
            }
            FileConfiguration old = YamlConfiguration.loadConfiguration(legacy);
            Map<String, Integer> progress = new HashMap<>();
            for (String tree : old.getKeys(false)) {
                ConfigurationSection treeSection = old.getConfigurationSection(tree);
                if (treeSection == null) {
                    continue;
                }
                for (String id : treeSection.getKeys(false)) {
                    String fullId = tree + "/" + id;
                    int value = treeSection.getInt(id + ".progress");
                    if (treeSection.getBoolean(id + ".completed")) {
                        ConfiguredAdvancement conf = plugin.getAdvancementManager().getConfigured(fullId);
                        value = Math.max(value, conf == null ? 1 : conf.getAmount());
                    }
                    if (value > 0) {
                        progress.put(fullId, value);
                    }
                }
            }
            if (progress.isEmpty()) {
                continue;
            }
            FileConfiguration config = new YamlConfiguration();
            config.set("progress", progress);
            try {
                config.save(target);
                migrated++;
            } catch (IOException e) {
                plugin.getLogger().severe("Migration impossible pour " + legacy.getName() + " : " + e.getMessage());
            }
        }
        try {
            java.nio.file.Files.writeString(marker.toPath(), "Migration CustomAdvancements du " + LocalDateTime.now()
                + " : " + migrated + " joueurs migrés, " + skipped + " déjà présents, sur " + legacyFiles.length + " fichiers.\n");
        } catch (IOException e) {
            plugin.getLogger().warning("Impossible d'écrire " + marker.getName() + " : " + e.getMessage());
        }
        plugin.getLogger().info("Migration terminée : " + migrated + " joueurs avec progression, " + skipped + " déjà présents.");
    }

    // --- TÂCHE DE SAUVEGARDE AUTOMATIQUE ---

    private void startAutoSaveTask() {
        new BukkitRunnable() {
            @Override
            public void run() {
                for (UUID uuid : dirty) {
                    savePlayerData(uuid);
                }
            }
        }.runTaskTimerAsynchronously(plugin, 20 * 60 * 5, 20 * 60 * 5); // Toutes les 5 minutes
    }
}
