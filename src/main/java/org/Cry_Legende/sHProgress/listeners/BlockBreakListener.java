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
import org.bukkit.event.block.BlockBreakEvent;

public class BlockBreakListener implements Listener {
    private final AdvancementManager manager;
    private final PlayerDataManager dataManager;

    public BlockBreakListener(SHProgress plugin) {
        this.manager = plugin.getAdvancementManager();
        this.dataManager = plugin.getPlayerDataManager();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        Material brokenBlockType = event.getBlock().getType();

        // Événement très fréquent (générateurs) : seuls les progrès "blockbreak" sont parcourus
        for (ConfiguredAdvancement advConfig : manager.getByType("blockbreak")) {
            if (advConfig.matches(brokenBlockType.name())) {
                dataManager.incrementProgression(player, advConfig, 1);
            }
        }
    }
}