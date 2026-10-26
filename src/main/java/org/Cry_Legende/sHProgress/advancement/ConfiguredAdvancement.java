package org.Cry_Legende.sHProgress.advancement;

import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class ConfiguredAdvancement {

    private final String tabId;
    private final String id;
    private final String type;
    private final String value;
    private final Set<String> values = new HashSet<>();
    private final int amount;
    private final String parentId;

    // Display
    private final String displayName;
    private final String displayItem;
    private final String description;
    private final String frameType;
    private final boolean toast;
    private final boolean announceToChat;
    // null = position calculée automatiquement par AdvancementManager
    private final Float x, y;

    // Rewards
    private final List<String> rewardCommands;
    private final List<String> rewardMessages;

    public ConfiguredAdvancement(String tabId, String id, ConfigurationSection config) {
        this.tabId = tabId;
        this.id = id;
        this.type = config.getString("type", "impossible").toLowerCase(Locale.ROOT);
        this.value = config.getString("value", "none");
        for (String raw : value.split(",")) {
            values.add(raw.trim().toUpperCase(Locale.ROOT));
        }
        this.amount = Math.max(1, config.getInt("amount", 1));

        String parent = null;
        if (config.isConfigurationSection("requirements")) {
            for (String key : config.getConfigurationSection("requirements").getKeys(false)) {
                if ("advancement".equalsIgnoreCase(config.getString("requirements." + key + ".type"))) {
                    parent = config.getString("requirements." + key + ".value");
                    break;
                }
            }
        }
        // Format CustomAdvancements : "<arbre>.<progrès>" (ex: tree.charbon)
        this.parentId = parent == null ? "root" : parent.substring(parent.indexOf('.') + 1);

        ConfigurationSection display = config.getConfigurationSection("display");
        if (display == null) {
            display = config.createSection("display");
        }
        this.displayName = display.getString("name", "Unnamed Advancement");
        this.displayItem = display.getString("item", "STONE");
        this.description = display.getString("description", "");
        this.frameType = display.getString("minecraft-gui-frame", "TASK").toUpperCase(Locale.ROOT);
        this.toast = display.getBoolean("minecraft-toast", true);
        this.announceToChat = display.getBoolean("minecraft-chat-announce", false);

        // Coordonnées explicites si présentes, sinon placement automatique (gui_location: auto)
        if (display.isSet("x") && display.isSet("y")) {
            this.x = (float) display.getDouble("x");
            this.y = (float) display.getDouble("y");
        } else {
            this.x = null;
            this.y = null;
        }

        // Chargement des récompenses
        this.rewardCommands = new ArrayList<>();
        this.rewardMessages = new ArrayList<>();
        ConfigurationSection rewardsSection = config.getConfigurationSection("rewards");
        if (rewardsSection != null) {
            for (String key : rewardsSection.getKeys(false)) {
                ConfigurationSection reward = rewardsSection.getConfigurationSection(key);
                if (reward != null) {
                    String rewardType = reward.getString("type");
                    if ("command".equalsIgnoreCase(rewardType)) {
                        rewardCommands.add(reward.getString("value"));
                    } else if ("message".equalsIgnoreCase(rewardType)) {
                        rewardMessages.add(reward.getString("value"));
                    }
                }
            }
        }
    }

    /**
     * "any" accepte tout ; sinon compare au nom du Material / EntityType (insensible à la casse).
     */
    public boolean matches(String name) {
        return values.contains("ANY") || values.contains(name.toUpperCase(Locale.ROOT));
    }

    /**
     * Nom exact parmi les valeurs listées, sans le mot-clé "any" (blockplace, consume).
     */
    public boolean matchesExact(String name) {
        return values.contains(name.toUpperCase(Locale.ROOT));
    }

    // Getters
    public String getFullId() {
        return tabId + "/" + id;
    }

    public String getTabId() {
        return tabId;
    }

    public String getId() {
        return id;
    }

    public String getType() {
        return type;
    }

    public String getValue() {
        return value;
    }

    public int getAmount() {
        return amount;
    }

    public String getParentId() {
        return parentId;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getDisplayItem() {
        return displayItem;
    }

    public String getDescription() {
        return description;
    }

    public String getFrameType() {
        return frameType;
    }

    public boolean isToast() {
        return toast;
    }

    public boolean isAnnounceToChat() {
        return announceToChat;
    }

    public Float getX() {
        return x;
    }

    public Float getY() {
        return y;
    }

    public List<String> getRewardCommands() {
        return rewardCommands;
    }

    public List<String> getRewardMessages() {
        return rewardMessages;
    }
}
