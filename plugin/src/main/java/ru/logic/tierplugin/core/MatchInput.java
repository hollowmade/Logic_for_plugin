package ru.logic.tierplugin.core;

/**
 * Everything the rating core needs to know about a finished fight.
 *
 * @param winnerScore    e.g. rounds/kills won by the winner
 * @param loserScore     e.g. rounds/kills won by the loser
 * @param durationMillis for a LEAVE: from the start until the loser disconnected
 */
public record MatchInput(Gamemode gamemode,
                         int winnerScore, int loserScore,
                         FightMetrics winnerMetrics, FightMetrics loserMetrics,
                         long durationMillis, EndReason endReason) {

    /** A fight decided by a kill. */
    public MatchInput(Gamemode gamemode, int winnerScore, int loserScore,
                      FightMetrics winnerMetrics, FightMetrics loserMetrics, long durationMillis) {
        this(gamemode, winnerScore, loserScore, winnerMetrics, loserMetrics, durationMillis, EndReason.KILL);
    }

    /** True if at least one hit landed: the fight actually happened. */
    public boolean anyHits() {
        return winnerMetrics.hits() > 0 || loserMetrics.hits() > 0;
    }
}
