package org.Cry_Legende.sHProgress.advancement;

import com.fren_gor.ultimateAdvancementAPI.AdvancementTab;
import com.fren_gor.ultimateAdvancementAPI.UltimateAdvancementAPI;
import com.fren_gor.ultimateAdvancementAPI.advancement.Advancement;
import com.fren_gor.ultimateAdvancementAPI.advancement.BaseAdvancement;
import com.fren_gor.ultimateAdvancementAPI.advancement.RootAdvancement;
import com.fren_gor.ultimateAdvancementAPI.advancement.display.AdvancementDisplay;
import com.fren_gor.ultimateAdvancementAPI.advancement.display.AdvancementFrameType;
import com.fren_gor.ultimateAdvancementAPI.events.PlayerLoadingCompletedEvent;
import org.Cry_Legende.sHProgress.SHProgress;
import org.Cry_Legende.sHProgress.data.PlayerDataManager;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.util.*;

public class AdvancementManager {

    private final SHProgress plugin;
    private final UltimateAdvancementAPI api;
    private final Map<String, ConfiguredAdvancement> configuredAdvancements = new LinkedHashMap<>();
    private final Map<String, Advancement> registeredAdvancements = new HashMap<>();
    // Progrès par type d'objectif (blockbreak, killentity...) pour ne pas tout parcourir à chaque événement
    private final Map<String, List<ConfiguredAdvancement>> byType = new HashMap<>();
    private final Map<String, RootAdvancement> roots = new HashMap<>();

    public AdvancementManager(SHProgress plugin, UltimateAdvancementAPI api) {
        this.plugin = plugin;
        this.api = api;
    }

    public void loadAdvancementTrees() {
        if (!registeredAdvancements.isEmpty()) {
            api.unregisterPluginAdvancementTabs();
        }
        configuredAdvancements.clear();
        registeredAdvancements.clear();
        byType.clear();
        roots.clear();

        File treesFolder = new File(plugin.getDataFolder(), "trees");
        if (!treesFolder.exists() || !treesFolder.isDirectory()) {
            plugin.getLogger().severe("Le dossier 'trees' est introuvable. Impossible de charger les progrès.");
            return;
        }

        File[] treeFiles = treesFolder.listFiles((dir, name) -> name.toLowerCase().endsWith(".yml"));
        if (treeFiles == null || treeFiles.length == 0) {
            plugin.getLogger().info("Aucun fichier d'arbre (.yml) trouvé dans le dossier /trees/. Aucun progrès ne sera chargé.");
            return;
        }

        for (File treeFile : treeFiles) {
            loadSingleTree(treeFile);
        }
        for (ConfiguredAdvancement adv : configuredAdvancements.values()) {
            if (registeredAdvancements.containsKey(adv.getFullId())) {
                byType.computeIfAbsent(adv.getType(), k -> new ArrayList<>()).add(adv);
            }
        }

        plugin.getLogger().info("Chargement de " + (registeredAdvancements.size() - roots.size()) + " progrès terminé pour " + roots.size() + " arbres.");
    }

    private void loadSingleTree(File treeFile) {
        String tabId = treeFile.getName().replace(".yml", "");
        FileConfiguration config = YamlConfiguration.loadConfiguration(treeFile);

        plugin.getLogger().info("Chargement de l'arbre : " + tabId);

        ConfigurationSection options = config.getConfigurationSection("options");
        if (options == null) {
            plugin.getLogger().warning("Le fichier '" + treeFile.getName() + "' n'a pas de section 'options'. L'onglet sera ignoré.");
            return;
        }

        List<ConfiguredAdvancement> advancementsToProcess = new ArrayList<>();
        ConfigurationSection advancementsSection = config.getConfigurationSection("advancements");
        if (advancementsSection != null) {
            for (String key : advancementsSection.getKeys(false)) {
                ConfigurationSection advSection = advancementsSection.getConfigurationSection(key);
                if (advSection == null) {
                    plugin.getLogger().warning("Progrès '" + key + "' de l'arbre '" + tabId + "' ignoré : bloc invalide.");
                    continue;
                }
                ConfiguredAdvancement configuredAdv = new ConfiguredAdvancement(tabId, key, advSection);
                advancementsToProcess.add(configuredAdv);
                this.configuredAdvancements.put(configuredAdv.getFullId(), configuredAdv);
            }
        }
        Map<String, float[]> positions = layout(advancementsToProcess);

        AdvancementTab tab = api.createAdvancementTab(tabId);
        float[] rootPos = positions.getOrDefault("root", new float[]{0, 0});
        AdvancementDisplay rootDisplay = new AdvancementDisplay.Builder(material(options.getString("item", "OAK_SAPLING")), color(options.getString("display_name", "Arbre de Progrès")))
            .description(lines(options.getString("description", "")))
            .frame(AdvancementFrameType.GOAL)
            .coords(rootPos[0], rootPos[1])
            .build();

        RootAdvancement root = new RootAdvancement(tab, "root", rootDisplay, options.getString("minecraft-gui-background", "textures/block/stone.png"));
        registeredAdvancements.put(tabId + "/root", root);
        roots.put(tabId, root);

        Set<BaseAdvancement> childAdvancements = new HashSet<>();
        int lastSize;
        do {
            lastSize = advancementsToProcess.size();
            Iterator<ConfiguredAdvancement> iterator = advancementsToProcess.iterator();
            while (iterator.hasNext()) {
                ConfiguredAdvancement confAdv = iterator.next();
                Advancement parent = registeredAdvancements.get(tabId + "/" + confAdv.getParentId());
                if (parent == null) {
                    continue;
                }
                float[] pos = positions.getOrDefault(confAdv.getId(), new float[]{1, 0});
                AdvancementDisplay display = new AdvancementDisplay.Builder(material(confAdv.getDisplayItem()), color(confAdv.getDisplayName()))
                    .description(lines(confAdv.getDescription()))
                    .frame(frame(confAdv.getFrameType()))
                    .showToast(confAdv.isToast())
                    .announceChat(confAdv.isAnnounceToChat())
                    .coords(pos[0], pos[1])
                    .build();

                BaseAdvancement advancement = new BaseAdvancement(confAdv.getId(), display, parent, confAdv.getAmount()) {
                    @Override
                    public void onGrant(Player player, boolean giveRewards) {
                        // Synchronisation silencieuse (connexion, migration) : ni toast, ni annonce, ni récompense
                        if (giveRewards) {
                            super.onGrant(player, true);
                        }
                    }

                    @Override
                    public void giveReward(Player player) {
                        confAdv.getRewardCommands().forEach(cmd ->
                            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd.replace("%player%", player.getName())));
                        confAdv.getRewardMessages().forEach(msg -> player.sendMessage(color(msg)));
                    }
                };
                childAdvancements.add(advancement);
                registeredAdvancements.put(confAdv.getFullId(), advancement);
                iterator.remove();
            }
        } while (lastSize > advancementsToProcess.size() && !advancementsToProcess.isEmpty());

        if (!advancementsToProcess.isEmpty()) {
            plugin.getLogger().severe("Impossible de charger les progrès suivants pour l'arbre '" + tabId + "' (dépendances manquantes ou circulaires):");
            advancementsToProcess.forEach(adv -> plugin.getLogger().severe("- " + adv.getId()));
        }

        tab.registerAdvancements(root, childAdvancements);
        tab.automaticallyShowToPlayers();
        tab.registerEvent(PlayerLoadingCompletedEvent.class, e -> syncPlayer(e.getPlayer(), tabId));
    }

    /**
     * Placement automatique : x = profondeur dans l'arbre, y = une ligne par feuille,
     * chaque parent centré sur ses enfants. Les coordonnées écrites dans le YML restent prioritaires.
     */
    private Map<String, float[]> layout(List<ConfiguredAdvancement> advancements) {
        Map<String, List<ConfiguredAdvancement>> children = new LinkedHashMap<>();
        for (ConfiguredAdvancement adv : advancements) {
            children.computeIfAbsent(adv.getParentId(), k -> new ArrayList<>()).add(adv);
        }
        Map<String, float[]> positions = new HashMap<>();
        place("root", 0, children, positions, new float[]{0});
        for (ConfiguredAdvancement adv : advancements) {
            if (adv.getX() != null) {
                positions.put(adv.getId(), new float[]{adv.getX(), adv.getY()});
            }
        }
        return positions;
    }

    private float place(String id, int depth, Map<String, List<ConfiguredAdvancement>> children, Map<String, float[]> positions, float[] nextRow) {
        if (positions.containsKey(id)) {
            return positions.get(id)[1];
        }
        positions.put(id, new float[]{depth, 0}); // protège contre les boucles
        List<ConfiguredAdvancement> kids = children.getOrDefault(id, List.of());
        float y;
        if (kids.isEmpty()) {
            y = nextRow[0]++;
        } else {
            float first = place(kids.get(0).getId(), depth + 1, children, positions, nextRow);
            float last = first;
            for (int i = 1; i < kids.size(); i++) {
                last = place(kids.get(i).getId(), depth + 1, children, positions, nextRow);
            }
            y = (first + last) / 2f;
        }
        positions.put(id, new float[]{depth, y});
        return y;
    }

    /**
     * Pousse la progression enregistrée par SHProgress vers UltimateAdvancementAPI, sans toast ni récompense.
     */
    private void syncPlayer(Player player, String tabId) {
        RootAdvancement root = roots.get(tabId);
        if (root == null) {
            return;
        }
        root.grant(player, false);
        PlayerDataManager data = plugin.getPlayerDataManager();
        UUID uuid = player.getUniqueId();
        data.loadIfAbsent(uuid);
        for (ConfiguredAdvancement conf : configuredAdvancements.values()) {
            Advancement adv = registeredAdvancements.get(conf.getFullId());
            if (adv == null || !conf.getTabId().equals(tabId)) {
                continue;
            }
            int target = Math.min(data.getProgression(uuid, conf.getFullId()), conf.getAmount());
            if (adv.getProgression(player) != target) {
                adv.setProgression(player, target, false);
            }
        }
    }

    /**
     * Après un rechargement : les joueurs déjà connectés ne déclenchent pas PlayerLoadingCompletedEvent.
     */
    public void syncOnlinePlayers() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            for (String tabId : roots.keySet()) {
                try {
                    syncPlayer(player, tabId);
                } catch (RuntimeException e) {
                    plugin.getLogger().warning("Synchronisation impossible pour " + player.getName() + " : " + e.getMessage());
                }
            }
        }
    }

    private Material material(String name) {
        Material material = Material.matchMaterial(name);
        if (material == null || !material.isItem()) {
            plugin.getLogger().warning("Objet inconnu '" + name + "', STONE utilisé à la place.");
            return Material.STONE;
        }
        return material;
    }

    private static AdvancementFrameType frame(String name) {
        try {
            return AdvancementFrameType.valueOf(name);
        } catch (IllegalArgumentException e) {
            return AdvancementFrameType.TASK;
        }
    }

    private static String color(String text) {
        return ChatColor.translateAlternateColorCodes('&', text);
    }

    private static List<String> lines(String text) {
        return Arrays.asList(color(text).split("\n"));
    }

    public Advancement getAdvancement(String fullId) {
        return registeredAdvancements.get(fullId);
    }

    public ConfiguredAdvancement getConfigured(String fullId) {
        return configuredAdvancements.get(fullId);
    }

    public List<ConfiguredAdvancement> getByType(String type) {
        return byType.getOrDefault(type, List.of());
    }

    public Collection<ConfiguredAdvancement> getConfiguredAdvancements() {
        return configuredAdvancements.values();
    }

    public List<String> getAdvancementIds() {
        return new ArrayList<>(registeredAdvancements.keySet());
    }
}
