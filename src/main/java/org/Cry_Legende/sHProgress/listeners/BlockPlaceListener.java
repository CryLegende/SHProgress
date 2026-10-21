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
import org.bukkit.event.block.BlockPlaceEvent;

public class BlockPlaceListener implements Listener {
    private final AdvancementManager manager;
    private final PlayerDataManager dataManager;

    public BlockPlaceListener(SHProgress plugin) {
        this.manager = plugin.getAdvancementManager();
        this.dataManager = plugin.getPlayerDataManager();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        Player player = event.getPlayer();
        Material placedBlockType = event.getBlock().getType();

        for (ConfiguredAdvancement advConfig : manager.getConfiguredAdvancements()) {
            if ("blockplace".equalsIgnoreCase(advConfig.getType())) {
                String[] requiredBlocks = advConfig.getValue().split(",");
                for (String blockName : requiredBlocks) {
                    if (placedBlockType.name().equalsIgnoreCase(blockName.trim())) {
                        dataManager.incrementProgression(player, advConfig, 1);
                        break;
                    }
                }
            }
        }
    }
}