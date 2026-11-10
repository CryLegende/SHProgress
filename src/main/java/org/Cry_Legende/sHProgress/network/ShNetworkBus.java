package org.Cry_Legende.sHProgress.network;

import org.Cry_Legende.sHNetwork.api.SHNetworkAPI;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/** Pont vers SHNetwork. Cette classe n'est chargée que si SHNetwork est installé. */
public final class ShNetworkBus implements NetBus {

    private final SHNetworkAPI api;

    private ShNetworkBus() {
        this.api = SHNetworkAPI.get();
    }

    /** Point d'entrée : le plugin ne résout cette classe qu'à cet appel, donc jamais sans SHNetwork. */
    public static NetBus open() {
        return new ShNetworkBus();
    }

    @Override
    public boolean isMainThread() {
        return api.isMainThread();
    }

    @Override
    public void sync(Runnable task) {
        api.sync(task);
    }

    @Override
    public void registerSaveTask(Function<Player, CompletableFuture<Void>> task) {
        api.registerSaveTask("SHProgress", task);
    }

    @Override
    public CompletableFuture<Boolean> awaitSaved(UUID uuid, Duration timeout) {
        return api.awaitSaved(uuid, timeout);
    }
}
