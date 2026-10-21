package org.Cry_Legende.sHProgress.listeners;

import org.Cry_Legende.sHProgress.data.PlayerDataManager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

public class PlayerConnectionListener implements Listener {

    private final PlayerDataManager dataManager;

    public PlayerConnectionListener(PlayerDataManager dataManager) {
        this.dataManager = dataManager;
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        // Charge les données du joueur depuis son fichier .yml
        dataManager.loadPlayerData(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        // Sauvegarde et retire les données du joueur du cache
        dataManager.unloadPlayerData(event.getPlayer().getUniqueId());
    }
}