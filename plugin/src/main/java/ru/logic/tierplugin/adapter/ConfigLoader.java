package ru.logic.tierplugin.adapter;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import ru.logic.tierplugin.core.CoreConfig;
import ru.logic.tierplugin.core.Gamemode;
import ru.logic.tierplugin.core.Tier;
import ru.logic.tierplugin.fight.FightSettings;
import ru.logic.tierplugin.storage.DatabaseSettings;
import ru.logic.tierplugin.storage.SqlDialect;
import ru.logic.tierplugin.tracker.TrackerSettings;

import java.io.File;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Translates config.yml into Bukkit-free settings objects
 * ({@link CoreConfig} for the rating core, {@link DatabaseSettings} for storage,
 * {@link TrackerSettings} for the metrics tracker).
 * Missing keys fall back to {@link CoreConfig#defaults()}.
 */
public final class ConfigLoader {

    private ConfigLoader() {}

    public static CoreConfig load(FileConfiguration cfg, Logger log) {
        CoreConfig d = CoreConfig.defaults();

        // ── Tiers ──────────────────────────────────────────────
        Map<Tier, CoreConfig.TierThreshold> tiers = new EnumMap<>(Tier.class);
        for (Tier tier : Tier.values()) {
            CoreConfig.TierThreshold def = d.tiers().get(tier);
            ConfigurationSection s = cfg.getConfigurationSection("tiers." + tier.name());
            if (s == null) {
                log.warning("В config.yml нет раздела tiers." + tier.name() + " — используются значения по умолчанию.");
                tiers.put(tier, def);
                continue;
            }
            tiers.put(tier, new CoreConfig.TierThreshold(
                    s.getDouble("min_elo", def.minElo()),
                    s.getDouble("min_skill", def.minSkill()),
                    s.getDouble("min_confidence", def.minConfidence()),
                    s.getInt("min_fights", def.minFights())));
        }

        // ── Elo ────────────────────────────────────────────────
        CoreConfig.Elo de = d.elo();
        CoreConfig.Elo elo = new CoreConfig.Elo(
                cfg.getDouble("elo.initial_rating", de.initialRating()),
                cfg.getDouble("elo.initial_rd", de.initialRd()),
                cfg.getDouble("elo.initial_volatility", de.initialVolatility()),
                cfg.getDouble("elo.min_rd", de.minRd()),
                cfg.getDouble("elo.rd_decay_per_match", de.rdDecayPerMatch()),
                loadDiminishing(cfg.getConfigurationSection("elo.diminishing_returns"), de.diminishingReturns()),
                cfg.getInt("elo.max_daily_rated_matches_per_opponent", de.maxDailyRatedMatchesPerOpponent()),
                cfg.getDouble("elo.placement_k_multiplier", de.placementKMultiplier()));

        // ── Skill weights ──────────────────────────────────────
        CoreConfig.Skill ds = d.skill();
        Map<Gamemode, CoreConfig.Weights> weights = new EnumMap<>(Gamemode.class);
        for (Gamemode gm : Gamemode.values()) {
            CoreConfig.Weights def = ds.weightsFor(gm);
            ConfigurationSection s = cfg.getConfigurationSection("skill_weights." + gm.configKey());
            weights.put(gm, s == null ? def : new CoreConfig.Weights(
                    s.getDouble("accuracy", def.accuracy()),
                    s.getDouble("damage", def.damage()),
                    s.getDouble("combo", def.combo())));
        }
        CoreConfig.Skill skill = new CoreConfig.Skill(
                cfg.getInt("skill_weights.combo_cap", ds.comboCap()),
                cfg.getDouble("skill_weights.ema_alpha", ds.emaAlpha()),
                weights);

        // ── Tier rules ─────────────────────────────────────────
        CoreConfig.TierRules dr = d.tierRules();
        CoreConfig.TierRules rules = new CoreConfig.TierRules(
                cfg.getInt("tier_rules.placement_fights", dr.placementFights()),
                cfg.getDouble("tier_rules.demotion_buffer_elo", dr.demotionBufferElo()));

        // ── Leaving a fight ────────────────────────────────────
        CoreConfig.Leave dl = d.leave();
        List<Double> penalties = cfg.getDoubleList("leave.penalty_multipliers");
        CoreConfig.Leave leave = new CoreConfig.Leave(
                penalties.isEmpty() ? dl.penaltyMultipliers()
                        : penalties.stream().mapToDouble(Double::doubleValue).toArray(),
                cfg.getLong("leave.real_fight_seconds", dl.realFightMillis() / 1000) * 1000,
                cfg.getInt("leave.window_hours", dl.windowHours()),
                cfg.getInt("leave.cooldown_after_leaves", dl.cooldownAfterLeaves()),
                cfg.getInt("leave.cooldown_minutes", dl.cooldownMinutes()));

        return new CoreConfig(
                tiers,
                elo,
                skill,
                rules,
                cfg.getInt("match_rating.score_diff_cap", d.scoreDiffCap()),
                leave);
    }

    public static FightSettings fight(FileConfiguration cfg) {
        return new FightSettings(cfg.getInt("leave.reconnect_grace_seconds", FightSettings.DEFAULTS.reconnectGraceSeconds()));
    }

    public static TrackerSettings tracker(FileConfiguration cfg) {
        TrackerSettings d = TrackerSettings.DEFAULTS;
        return new TrackerSettings(
                cfg.getLong("metrics.combo_timeout_ms", d.comboTimeoutMs()),
                cfg.getInt("metrics.combo_min_length", d.comboMinLength()));
    }

    public static DatabaseSettings database(FileConfiguration cfg, File dataFolder) {
        return new DatabaseSettings(
                SqlDialect.fromConfig(cfg.getString("database.type", "sqlite")),
                new File(dataFolder, cfg.getString("database.file", "tierplugin.db")),
                cfg.getString("database.host", "localhost"),
                cfg.getInt("database.port", 3306),
                cfg.getString("database.name", "tierplugin"),
                cfg.getString("database.user", "root"),
                cfg.getString("database.password", ""),
                cfg.getInt("database.pool-size", 5));
    }

    /** Reads {@code 1: 1.00, 2: 0.70, ...} in key order 1..N, stopping at the first gap. */
    private static double[] loadDiminishing(ConfigurationSection s, double[] fallback) {
        if (s == null) return fallback;
        List<Double> values = new ArrayList<>();
        for (int i = 1; s.contains(String.valueOf(i)); i++) {
            values.add(s.getDouble(String.valueOf(i)));
        }
        if (values.isEmpty()) return fallback;
        return values.stream().mapToDouble(Double::doubleValue).toArray();
    }
}
