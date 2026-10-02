package ru.logic.tierplugin.tracker;

import java.util.Optional;
import java.util.UUID;

/**
 * The only thing the metrics tracker knows about fights: who is fighting whom.
 * Duels, arenas or any other fight source implement this; the tracker records
 * telemetry only for players inside an active fight and only against their opponent.
 */
public interface ActiveFights {

    /** @return the fight {@code player} is currently in, or empty */
    Optional<FightRef> fightOf(UUID player);

    /** Identity and participants of an active 1v1 fight. */
    record FightRef(UUID id, UUID playerA, UUID playerB) {

        public boolean involves(UUID player) {
            return playerA.equals(player) || playerB.equals(player);
        }

        public UUID opponentOf(UUID player) {
            return playerA.equals(player) ? playerB : playerA;
        }
    }
}
