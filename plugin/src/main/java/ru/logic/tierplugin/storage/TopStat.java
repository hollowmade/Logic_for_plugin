package ru.logic.tierplugin.storage;

/**
 * What a leaderboard can be sorted by. Rating-based stats come from the
 * {@code ratings} table; fight stats are aggregated over all fights that ended
 * by a kill ({@code metrics} joined with {@code matches}).
 */
public enum TopStat {
    ELO(true, "r.elo"),
    SKILL(true, "r.skill"),
    WINRATE(true, "r.wins * 1.0 / NULLIF(r.wins + r.losses, 0)"),
    WINS(true, "r.wins"),
    ACCURACY(false, "SUM(s.hits) * 1.0 / NULLIF(SUM(s.swings), 0)"),
    /** Mean length of all combos (weighted by how many combos each fight had). */
    COMBO(false, "SUM(s.avg_combo * s.combo_count) / NULLIF(SUM(s.combo_count), 0)"),
    BEST_COMBO(false, "MAX(s.max_combo)"),
    CPS(false, "AVG(s.avg_cps)");

    private final boolean fromRatings;
    private final String sqlValue;

    TopStat(boolean fromRatings, String sqlValue) {
        this.fromRatings = fromRatings;
        this.sqlValue = sqlValue;
    }

    boolean fromRatings() { return fromRatings; }

    String sqlValue() { return sqlValue; }
}
