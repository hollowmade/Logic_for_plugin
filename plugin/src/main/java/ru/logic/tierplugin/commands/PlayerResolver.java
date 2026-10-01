package ru.logic.tierplugin.commands;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import ru.logic.tierplugin.storage.Storage;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/** Resolves a player name to a UUID: online players first, then anyone who ever joined. */
final class PlayerResolver {

    private PlayerResolver() {}

    static CompletableFuture<Optional<Storage.PlayerRef>> resolve(Storage storage, String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) {
            return CompletableFuture.completedFuture(
                    Optional.of(new Storage.PlayerRef(online.getUniqueId(), online.getName())));
        }
        return storage.findPlayer(name);
    }
}
