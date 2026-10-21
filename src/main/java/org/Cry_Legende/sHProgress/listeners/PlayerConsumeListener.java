package org.Cry_Legende.sHProgress.listeners;

import org.Cry_Legende.sHProgress.SHProgress;
import org.Cry_Legende.sHProgress.advancement.AdvancementManager;
import org.Cry_Legende.sHProgress.advancement.ConfiguredAdvancement;
import org.Cry_Legende.sHProgress.data.PlayerDataManager;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerItemConsumeEvent;

public class PlayerConsumeListener implements Listener {
    private final AdvancementManager manager;
    private final PlayerDataManager dataManager;

    public PlayerConsumeListener(SHProgress plugin) {
        this.manager = plugin.getAdvancementManager();
        this.dataManager = plugin.getPlayerDataManager();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerConsume(PlayerItemConsumeEvent event) {
        Player player = event.getPlayer();
        Material consumedItemType = event.getItem().getType();

        for (ConfiguredAdvancement advConfig : manager.getConfiguredAdvancements()) {
            if ("consume".equalsIgnoreCase(advConfig.getType())) {
                String[] requiredItems = advConfig.getValue().split(",");
                for (String itemName : requiredItems) {
                    if (consumedItemType.name().equalsIgnoreCase(itemName.trim())) {
                        dataManager.incrementProgression(player, advConfig, 1);
                        break;
                    }
                }
            }
        }
    }
}