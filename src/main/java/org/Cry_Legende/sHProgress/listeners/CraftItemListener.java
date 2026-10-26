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
import org.bukkit.event.inventory.ClickType;
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
        ItemStack result = event.getRecipe().getResult();
        Material craftedItemType = result.getType();
        int craftAmount = result.getAmount();
        ClickType click = event.getClick();
        if (event.isShiftClick()) {
            // Shift-clic : autant de crafts que la plus petite pile de la grille le permet, dans la limite de la place libre
            int crafts = Integer.MAX_VALUE;
            for (ItemStack ingredient : event.getInventory().getMatrix()) {
                if (ingredient != null && !ingredient.getType().isAir()) {
                    crafts = Math.min(crafts, ingredient.getAmount());
                }
            }
            crafts = Math.min(crafts == Integer.MAX_VALUE ? 1 : crafts, room(player, result) / Math.max(1, craftAmount));
            if (crafts <= 0) {
                return; // inventaire plein : rien n'est fabriqué, l'événement se répète à chaque clic
            }
            craftAmount *= crafts;
        } else if (click == ClickType.LEFT || click == ClickType.RIGHT) {
            // Curseur occupé par un autre objet ou pile pleine : le clic ne fabrique rien
            ItemStack cursor = event.getCursor();
            if (cursor != null && !cursor.getType().isAir()
                && (!cursor.isSimilar(result) || cursor.getAmount() + craftAmount > cursor.getMaxStackSize())) {
                return;
            }
        } else if (click == ClickType.NUMBER_KEY) {
            ItemStack target = player.getInventory().getItem(event.getHotbarButton());
            if (target != null && !target.getType().isAir()) {
                return;
            }
        } else if (click == ClickType.SWAP_OFFHAND) {
            if (!player.getInventory().getItemInOffHand().getType().isAir()) {
                return;
            }
        } else if (click != ClickType.DROP && click != ClickType.CONTROL_DROP) {
            return; // double-clic, clic molette (créatif)... : aucune fabrication
        }

        for (ConfiguredAdvancement advConfig : manager.getByType("craftitem")) {
            if (advConfig.matches(craftedItemType.name())) {
                dataManager.incrementProgression(player, advConfig, craftAmount);
            }
        }
    }

    // Place disponible pour le résultat dans l'inventaire principal
    private static int room(Player player, ItemStack result) {
        int room = 0;
        int max = result.getMaxStackSize();
        for (ItemStack item : player.getInventory().getStorageContents()) {
            if (item == null || item.getType().isAir()) {
                room += max;
            } else if (item.isSimilar(result)) {
                room += Math.max(0, max - item.getAmount());
            }
        }
        return room;
    }
}