package org.Cry_Legende.sHProgress;

import com.fren_gor.ultimateAdvancementAPI.AdvancementMain;
import com.fren_gor.ultimateAdvancementAPI.UltimateAdvancementAPI;
import org.Cry_Legende.sHProgress.advancement.AdvancementManager;
import org.Cry_Legende.sHProgress.command.CommandManager;
import org.Cry_Legende.sHProgress.data.PlayerDataManager;
import org.Cry_Legende.sHProgress.listeners.*;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

public final class SHProgress extends JavaPlugin {

    private static SHProgress instance;
    private AdvancementMain advancementMain;
    private UltimateAdvancementAPI api;
    private AdvancementManager advancementManager;
    private PlayerDataManager playerDataManager;

    @Override
    public void onEnable() {
        instance = this;

        // Étape 1 : Vérifier la présence de l'API
        if (getServer().getPluginManager().getPlugin("UltimateAdvancementAPI") == null) {
            getLogger().severe("UltimateAdvancementAPI non trouvé ! Le plugin va se désactiver.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        // Étape 2 : Charger l'API de manière sécurisée
        if (!AdvancementMain.isEnabled()) {
            getLogger().info("Initialisation de l'instance de UltimateAdvancementAPI par SHProgress...");
            this.advancementMain = new AdvancementMain(this);
            advancementMain.load();
            advancementMain.enableInMemory();
        } else {
            getLogger().info("UltimateAdvancementAPI est déjà géré par un autre plugin. SHProgress s'y attache.");
        }

        this.api = UltimateAdvancementAPI.getInstance(this);

        getLogger().info("Initialisation de SHProgress...");

        // Étape 3 : Initialiser le gestionnaire de données de sauvegarde
        this.playerDataManager = new PlayerDataManager(this);

        // Étape 4 : Créer la configuration par défaut (ou reprendre l'arbre de CustomAdvancements)
        setupDefaultConfig();

        // Étape 5 : Initialiser le gestionnaire de progrès qui lit les fichiers de config
        this.advancementManager = new AdvancementManager(this, api);
        this.advancementManager.loadAdvancementTrees();

        // Étape 6 : Reprendre la progression des joueurs de CustomAdvancements (une seule fois)
        playerDataManager.migrateFromCustomAdvancements();

        // Étape 7 : Enregistrer les commandes
        CommandManager commandManager = new CommandManager(this);
        getCommand("shprogress").setExecutor(commandManager);
        getCommand("shprogress").setTabCompleter(commandManager);

        // Étape 8 : Enregistrer tous les listeners
        registerListeners();

        // Étape 9 : Cacher les progrès vanilla (remove-default-trees de CustomAdvancements)
        try {
            api.disableVanillaAdvancements();
        } catch (Exception e) {
            getLogger().warning("Impossible de cacher les progrès vanilla : " + e.getMessage());
        }

        getLogger().info("SHProgress a été activé avec succès !");
    }

    @Override
    public void onDisable() {
        // Sauvegarde de tous les joueurs en ligne avant l'arrêt
        if (playerDataManager != null) {
            getLogger().info("Sauvegarde finale des données de progression...");
            playerDataManager.saveAll();
        }

        if (this.advancementMain != null) {
            this.advancementMain.disable();
        }
        getLogger().info("SHProgress a été désactivé.");
    }

    /**
     * Premier démarrage : reprend tels quels les arbres de CustomAdvancements (mêmes identifiants "tree/...",
     * utilisés par SHCrates et par la migration), sinon installe l'arbre par défaut.
     */
    private void setupDefaultConfig() {
        File treesFolder = new File(getDataFolder(), "trees");
        if (treesFolder.exists()) {
            return;
        }
        treesFolder.mkdirs();
        File legacyTrees = new File(getDataFolder().getParentFile(), "CustomAdvancements/advancement-trees");
        File[] legacyFiles = legacyTrees.listFiles((dir, name) -> name.endsWith(".yml"));
        if (legacyFiles != null && legacyFiles.length > 0) {
            for (File legacy : legacyFiles) {
                try {
                    Files.copy(legacy.toPath(), new File(treesFolder, legacy.getName()).toPath(), StandardCopyOption.COPY_ATTRIBUTES);
                    getLogger().info("Arbre CustomAdvancements repris : " + legacy.getName());
                } catch (IOException e) {
                    getLogger().severe("Copie de " + legacy.getName() + " impossible : " + e.getMessage());
                }
            }
            return;
        }
        saveResource("default_tree.yml", false);
        File exampleFile = new File(getDataFolder(), "default_tree.yml");
        exampleFile.renameTo(new File(treesFolder, "tree.yml"));
    }

    private void registerListeners() {
        PluginManager pm = getServer().getPluginManager();
        pm.registerEvents(new PlayerConnectionListener(playerDataManager), this);
        pm.registerEvents(new BlockBreakListener(this), this);
        pm.registerEvents(new BlockPlaceListener(this), this);
        pm.registerEvents(new CraftItemListener(this), this);
        pm.registerEvents(new EntityKillListener(this), this);
        pm.registerEvents(new PlayerConsumeListener(this), this);
    }

    public static SHProgress getInstance() {
        return instance;
    }

    public AdvancementManager getAdvancementManager() {
        return advancementManager;
    }

    public PlayerDataManager getPlayerDataManager() {
        return playerDataManager;
    }
}
