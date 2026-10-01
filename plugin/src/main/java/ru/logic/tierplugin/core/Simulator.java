package ru.logic.tierplugin.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * Offline match simulator for sanity-checking the formulas.
 * <p>
 * Virtual players get a hidden "true skill" on the Elo scale. Matches are decided
 * by the Elo win probability of the true skills, and combat metrics are drawn
 * around that skill. If the system is sound, the rating it ends up with should
 * rank players in the same order as the hidden true skill.
 * <p>
 * Run standalone: {@code gradlew runSimulation}.
 */
public final class Simulator {

    private static final double TRUE_SKILL_MIN = 600;
    private static final double TRUE_SKILL_MAX = 2200;

    private Simulator() {}

    /**
     * @param spearman rank correlation of final Elo vs hidden true skill (1.0 = perfect order)
     * @param top      best players by final Elo, for eyeballing
     */
    public record Report(int players, int matches, double spearman,
                         Map<Tier, Integer> tierCounts, int provisional, int unranked,
                         List<Row> top) {}

    public record Row(String name, double trueSkill, double elo, double skill, Tier tier, int fights) {}

    /** Outcome of one player feeding another free wins all day. */
    public record FarmReport(int matches, double eloGained, double eloGainedWithoutProtection, int ratedFights) {}

    private record SimPlayer(String name, double trueSkill, ModeRating rating) {}

    /**
     * Simulates a ladder: every day each player plays {@code matchesPerDay} matches
     * against one of the closest-rated opponents (simple matchmaking).
     */
    public static Report run(CoreConfig cfg, int players, int days, int matchesPerDay, long seed) {
        Random rnd = new Random(seed);
        RatingService service = new RatingService(cfg);

        List<SimPlayer> pool = new ArrayList<>();
        for (int i = 0; i < players; i++) {
            double trueSkill = clamp(1400 + rnd.nextGaussian() * 300, TRUE_SKILL_MIN, TRUE_SKILL_MAX);
            pool.add(new SimPlayer("bot" + i, trueSkill, service.newRating(UUID.randomUUID(), Gamemode.SWORD)));
        }

        int matches = 0;
        for (int day = 0; day < days; day++) {
            Map<String, Integer> pairCountToday = new HashMap<>();
            for (int round = 0; round < matchesPerDay; round++) {
                for (SimPlayer a : pool) {
                    SimPlayer b = pickOpponent(pool, a, rnd);
                    playMatch(service, a, b, pairCountToday, rnd);
                    matches++;
                }
            }
        }

        Map<Tier, Integer> tierCounts = new EnumMap<>(Tier.class);
        int provisional = 0, unranked = 0;
        for (SimPlayer p : pool) {
            ModeRating r = p.rating();
            if (r.isProvisional()) provisional++;
            if (r.getTier() == null) unranked++;
            else tierCounts.merge(r.getTier(), 1, Integer::sum);
        }

        List<Row> top = pool.stream()
                .sorted(Comparator.comparingDouble((SimPlayer p) -> p.rating().getElo()).reversed())
                .limit(10)
                .map(p -> new Row(p.name(), p.trueSkill(), p.rating().getElo(),
                        p.rating().getSkillScore(), p.rating().getTier(), p.rating().getFights()))
                .toList();

        return new Report(players, matches, spearman(pool), tierCounts, provisional, unranked, top);
    }

    /**
     * Two accounts of equal strength: the booster loses on purpose {@code matches} times in one day.
     * Compares the farmer's Elo gain with and without repeat-match protection.
     */
    public static FarmReport farm(CoreConfig cfg, int matches) {
        CoreConfig.Elo e = cfg.elo();
        CoreConfig noProtection = new CoreConfig(cfg.tiers(),
                new CoreConfig.Elo(e.initialRating(), e.initialRd(), e.initialVolatility(), e.minRd(),
                        e.rdDecayPerMatch(), new double[]{1.0}, Integer.MAX_VALUE),
                cfg.skill(), cfg.tierRules(), cfg.scoreDiffCap());

        ModeRating farmer = farmOneDay(cfg, matches);
        ModeRating unprotected = farmOneDay(noProtection, matches);
        double start = e.initialRating();
        return new FarmReport(matches, farmer.getElo() - start, unprotected.getElo() - start, farmer.getFights());
    }

    /** @return the farmer's rating after beating the same booster {@code matches} times in one day */
    private static ModeRating farmOneDay(CoreConfig cfg, int matches) {
        RatingService service = new RatingService(cfg);
        ModeRating farmer = service.newRating(UUID.randomUUID(), Gamemode.SWORD);
        ModeRating booster = service.newRating(UUID.randomUUID(), Gamemode.SWORD);
        for (int i = 1; i <= matches; i++) {
            service.process(farmer, booster, fakeMatch(), i);
        }
        return farmer;
    }

    private static MatchInput fakeMatch() {
        return new MatchInput(Gamemode.SWORD, 1, 0,
                new FightMetrics(20, 10, 100, 40, 6), new FightMetrics(5, 25, 40, 100, 2), 30_000);
    }

    // ── internals ───────────────────────────────────────────────

    /** Closest by current Elo among a few random candidates. */
    private static SimPlayer pickOpponent(List<SimPlayer> pool, SimPlayer self, Random rnd) {
        SimPlayer best = null;
        for (int i = 0; i < 6; i++) {
            SimPlayer c = pool.get(rnd.nextInt(pool.size()));
            if (c == self) continue;
            if (best == null || Math.abs(c.rating().getElo() - self.rating().getElo())
                    < Math.abs(best.rating().getElo() - self.rating().getElo())) {
                best = c;
            }
        }
        return best != null ? best : pool.get((pool.indexOf(self) + 1) % pool.size());
    }

    private static void playMatch(RatingService service, SimPlayer a, SimPlayer b,
                                  Map<String, Integer> pairCountToday, Random rnd) {
        double pA = 1.0 / (1.0 + Math.pow(10, (b.trueSkill() - a.trueSkill()) / 400.0));
        boolean aWins = rnd.nextDouble() < pA;
        SimPlayer w = aWins ? a : b, l = aWins ? b : a;

        String key = a.name().compareTo(b.name()) < 0 ? a.name() + ":" + b.name() : b.name() + ":" + a.name();
        int matchNumber = pairCountToday.merge(key, 1, Integer::sum);

        MatchInput in = new MatchInput(Gamemode.SWORD, 1, 0,
                metrics(w.trueSkill(), true, rnd), metrics(l.trueSkill(), false, rnd), 30_000);
        service.process(w.rating(), l.rating(), in, matchNumber);
    }

    /** Draws plausible combat metrics around a true skill level. */
    private static FightMetrics metrics(double trueSkill, boolean won, Random rnd) {
        double q = (trueSkill - TRUE_SKILL_MIN) / (TRUE_SKILL_MAX - TRUE_SKILL_MIN); // 0..1
        int swings = 20 + rnd.nextInt(20);
        double acc = clamp(0.35 + 0.50 * q + rnd.nextGaussian() * 0.08, 0.05, 0.98);
        int hits = (int) Math.round(swings * acc);
        int damage = (int) clamp(60 + 140 * q + rnd.nextGaussian() * 20 + (won ? 15 : -15), 0, 400);
        int combo = (int) clamp(3 + 15 * q + rnd.nextGaussian() * 2, 0, hits);
        return new FightMetrics(hits, swings - hits, damage, 0, combo);
    }

    /** Spearman rank correlation between hidden true skill and final Elo. */
    private static double spearman(List<SimPlayer> pool) {
        int n = pool.size();
        double[] trueRank = ranks(pool, SimPlayer::trueSkill);
        double[] eloRank = ranks(pool, p -> p.rating().getElo());
        double d2 = 0;
        for (int i = 0; i < n; i++) d2 += Math.pow(trueRank[i] - eloRank[i], 2);
        return 1 - 6 * d2 / (n * ((double) n * n - 1));
    }

    private static double[] ranks(List<SimPlayer> pool, java.util.function.ToDoubleFunction<SimPlayer> key) {
        Integer[] idx = new Integer[pool.size()];
        for (int i = 0; i < idx.length; i++) idx[i] = i;
        java.util.Arrays.sort(idx, Comparator.comparingDouble(i -> key.applyAsDouble(pool.get(i))));
        double[] rank = new double[idx.length];
        for (int r = 0; r < idx.length; r++) rank[idx[r]] = r;
        return rank;
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    // ── standalone report ───────────────────────────────────────

    public static void main(String[] args) {
        CoreConfig cfg = CoreConfig.defaults();
        Report r = run(cfg, 200, 30, 6, 42);
        System.out.println(format(r));
        FarmReport f = farm(cfg, 100);
        System.out.println(format(f));
    }

    public static String format(Report r) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("Simulated %d players, %d matches%n", r.players(), r.matches()));
        sb.append(String.format("Rank correlation Elo vs true skill: %.3f%n", r.spearman()));
        sb.append("Tiers: ");
        r.tierCounts().forEach((t, c) -> sb.append(t).append('=').append(c).append(' '));
        sb.append(String.format("unranked=%d provisional=%d%n", r.unranked(), r.provisional()));
        sb.append("Top by Elo:\n");
        for (Row row : r.top()) {
            sb.append(String.format("  %-6s true=%4.0f elo=%4.0f skill=%4.1f tier=%s fights=%d%n",
                    row.name(), row.trueSkill(), row.elo(), row.skill(), row.tier(), row.fights()));
        }
        return sb.toString();
    }

    public static String format(FarmReport f) {
        return String.format("Farm test: %d wins vs the same booster in one day: +%.0f Elo"
                        + " (without protection: +%.0f), rated fights counted: %d%n",
                f.matches(), f.eloGained(), f.eloGainedWithoutProtection(), f.ratedFights());
    }
}
