package org.Cry_Legende.sHProgress.listeners;

import org.Cry_Legende.sHProgress.SHProgress;
import org.Cry_Legende.sHProgress.advancement.AdvancementManager;
import org.Cry_Legende.sHProgress.advancement.ConfiguredAdvancement;
import org.Cry_Legende.sHProgress.data.PlayerDataManager;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;

public class EntityKillListener implements Listener {
    private final AdvancementManager manager;
    private final PlayerDataManager dataManager;

    public EntityKillListener(SHProgress plugin) {
        this.manager = plugin.getAdvancementManager();
        this.dataManager = plugin.getPlayerDataManager();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityDeath(EntityDeathEvent event) {
        Player player = event.getEntity().getKiller();
        if (player == null) {
            return;
        }

        EntityType killedEntityType = event.getEntityType();

        // matches() gère le mot-clé "any"
        for (ConfiguredAdvancement advConfig : manager.getByType("killentity")) {
            if (advConfig.matches(killedEntityType.name())) {
                dataManager.incrementProgression(player, advConfig, 1);
            }
        }
    }
}