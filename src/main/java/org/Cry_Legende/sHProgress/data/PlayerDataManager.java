package org.Cry_Legende.sHProgress.data;

import com.fren_gor.ultimateAdvancementAPI.advancement.Advancement;
import org.Cry_Legende.sHProgress.SHProgress;
import org.Cry_Legende.sHProgress.advancement.ConfiguredAdvancement;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.File;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class PlayerDataManager {

    private final SHProgress plugin;
    private final File dataFolder;
    // Cache des joueurs en ligne ; maps concurrentes car la sauvegarde automatique est asynchrone
    private final Map<UUID, Map<String, Integer>> playerProgressCache = new ConcurrentHashMap<>();
    private final Set<UUID> dirty = ConcurrentHashMap.newKeySet();
    // La sauvegarde auto (asynchrone) et celle de la déconnexion ne doivent jamais écrire le même fichier en même temps
    private final Object saveLock = new Object();

    public PlayerDataManager(SHProgress plugin) {
        this.plugin = plugin;
        this.dataFolder = new File(plugin.getDataFolder(), "playerdata");
        if (!dataFolder.exists()) {
            dataFolder.mkdirs();
        }
        startAutoSaveTask();
    }

    // --- LOGIQUE DE CONNEXION / DÉCONNEXION ---

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

    public void loadIfAbsent(UUID uuid) {
        if (!playerProgressCache.containsKey(uuid)) {
            loadPlayerData(uuid);
        }
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
        if (playerProgressCache.containsKey(uuid)) {
            savePlayerData(uuid);
            playerProgressCache.remove(uuid);
        }
    }

    public void saveAll() {
        playerProgressCache.keySet().forEach(this::savePlayerData);
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
        loadIfAbsent(uuid);
        playerProgressCache.get(uuid).put(advancementId, newProgress);
        dirty.add(uuid);

        // Mise à jour visuelle via l'API (toast et récompenses si le progrès est terminé)
        Advancement advancement = plugin.getAdvancementManager().getAdvancement(advancementId);
        Player player = plugin.getServer().getPlayer(uuid);
        if (advancement != null && player != null) {
            advancement.setProgression(player, newProgress);
        }
    }

    /**
     * Comme CustomAdvancements : rien ne compte tant que le progrès parent n'est pas terminé.
     */
    public void incrementProgression(Player player, ConfiguredAdvancement advConfig, int amount) {
        UUID uuid = player.getUniqueId();
        loadIfAbsent(uuid);
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

        setProgression(uuid, fullId, Math.min(currentProgress + amount, maxProgress));
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
