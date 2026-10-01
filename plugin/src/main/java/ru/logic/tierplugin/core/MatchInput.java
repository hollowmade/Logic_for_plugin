package ru.logic.tierplugin.core;

/**
 * Everything the rating core needs to know about a finished fight.
 *
 * @param winnerScore e.g. rounds/kills won by the winner
 * @param loserScore  e.g. rounds/kills won by the loser
 */
public record MatchInput(Gamemode gamemode,
                         int winnerScore, int loserScore,
                         FightMetrics winnerMetrics, FightMetrics loserMetrics,
                         long durationMillis) {}
