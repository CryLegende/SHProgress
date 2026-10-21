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

        for (ConfiguredAdvancement advConfig : manager.getConfiguredAdvancements()) {
            if ("killentity".equalsIgnoreCase(advConfig.getType())) {
                if ("any".equalsIgnoreCase(advConfig.getValue())) {
                    dataManager.incrementProgression(player, advConfig, 1);
                    continue;
                }

                String[] requiredEntities = advConfig.getValue().split(",");
                for (String entityName : requiredEntities) {
                    if (killedEntityType.name().equalsIgnoreCase(entityName.trim())) {
                        dataManager.incrementProgression(player, advConfig, 1);
                        break;
                    }
                }
            }
        }
    }
}