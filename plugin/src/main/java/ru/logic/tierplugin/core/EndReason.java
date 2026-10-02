package ru.logic.tierplugin.core;

/** How a fight ended. */
public enum EndReason {
    /** The winner killed the opponent. */
    KILL,
    /** The loser left the server and did not come back within the reconnect grace period. */
    LEAVE
}
