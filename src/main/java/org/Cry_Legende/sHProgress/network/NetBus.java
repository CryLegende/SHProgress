package org.Cry_Legende.sHProgress.network;

import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/**
 * Ce dont la progression en réseau a besoin de SHNetwork. Seule {@link ShNetworkBus} le connaît : sans lui,
 * aucune classe de ce paquet n'est chargée.
 */
public interface NetBus {

    /** Vrai sur le fil principal du serveur (et non sur un fil de monde). */
    boolean isMainThread();

    /** Exécute la tâche sur le fil principal : tout de suite si on y est déjà, sinon au prochain tick. */
    void sync(Runnable task);

    /** Inscrit la sauvegarde exécutée au départ de chaque joueur, avant qu'il arrive sur un autre serveur. */
    void registerSaveTask(Function<Player, CompletableFuture<Void>> task);

    /**
     * Attend que le serveur de départ ait fini de sauvegarder le joueur. Le futur se termine hors du fil principal,
     * sur un fil à ne jamais bloquer.
     */
    CompletableFuture<Boolean> awaitSaved(UUID uuid, Duration timeout);
}
