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
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.inventory.ItemStack;

public class CraftItemListener implements Listener {
    private final AdvancementManager manager;
    private final PlayerDataManager dataManager;

    public CraftItemListener(SHProgress plugin) {
        this.manager = plugin.getAdvancementManager();
        this.dataManager = plugin.getPlayerDataManager();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCraftItem(CraftItemEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) {
            return;
        }
        Player player = (Player) event.getWhoClicked();
        Material craftedItemType = event.getRecipe().getResult().getType();
        int craftAmount = event.getRecipe().getResult().getAmount();
        if (event.isShiftClick()) {
            // Shift-clic : autant de crafts que la plus petite pile de la grille le permet
            int crafts = Integer.MAX_VALUE;
            for (ItemStack ingredient : event.getInventory().getMatrix()) {
                if (ingredient != null && !ingredient.getType().isAir()) {
                    crafts = Math.min(crafts, ingredient.getAmount());
                }
            }
            craftAmount *= crafts == Integer.MAX_VALUE ? 1 : crafts;
        }

        for (ConfiguredAdvancement advConfig : manager.getByType("craftitem")) {
            if (advConfig.matches(craftedItemType.name())) {
                dataManager.incrementProgression(player, advConfig, craftAmount);
            }
        }
    }
}