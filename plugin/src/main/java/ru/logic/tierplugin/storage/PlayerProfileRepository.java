package ru.logic.tierplugin.storage;

import ru.logic.tierplugin.LogicTierPlugin;
import ru.logic.tierplugin.core.PlayerProfile;
import ru.logic.tierplugin.core.Tier;

import java.sql.*;
import java.util.Optional;
import java.util.UUID;

/**
 * Provides CRUD operations for {@link PlayerProfile} entities.
 */
public class PlayerProfileRepository {

    private final LogicTierPlugin plugin;

    public PlayerProfileRepository(LogicTierPlugin plugin) {
        this.plugin = plugin;
    }

    public Optional<PlayerProfile> findByUuid(UUID uuid) {
        String sql = "SELECT * FROM player_profiles WHERE uuid = ?";
        try (Connection conn = plugin.getDatabase().getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                return Optional.of(map(rs));
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("findByUuid failed: " + e.getMessage());
        }
        return Optional.empty();
    }

    public void save(PlayerProfile p) {
        String sql = """
                INSERT INTO player_profiles
                (uuid, last_name, elo_rating, rating_deviation, volatility,
                 skill_score, confidence, total_fights, tier, provisional, last_updated)
                VALUES (?,?,?,?,?,?,?,?,?,?,?)
                ON CONFLICT(uuid) DO UPDATE SET
                  last_name        = excluded.last_name,
                  elo_rating       = excluded.elo_rating,
                  rating_deviation = excluded.rating_deviation,
                  volatility       = excluded.volatility,
                  skill_score      = excluded.skill_score,
                  confidence       = excluded.confidence,
                  total_fights     = excluded.total_fights,
                  tier             = excluded.tier,
                  provisional      = excluded.provisional,
                  last_updated     = excluded.last_updated
                """;
        try (Connection conn = plugin.getDatabase().getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, p.getUuid().toString());
            ps.setString(2, p.getLastName());
            ps.setDouble(3, p.getEloRating());
            ps.setDouble(4, p.getRatingDeviation());
            ps.setDouble(5, p.getVolatility());
            ps.setDouble(6, p.getSkillScore());
            ps.setDouble(7, p.getConfidence());
            ps.setInt(8, p.getTotalFights());
            ps.setString(9, p.getTier() != null ? p.getTier().name() : null);
            ps.setInt(10, p.isProvisional() ? 1 : 0);
            ps.setLong(11, System.currentTimeMillis());
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().severe("save profile failed: " + e.getMessage());
        }
    }

    private PlayerProfile map(ResultSet rs) throws SQLException {
        UUID uuid = UUID.fromString(rs.getString("uuid"));
        PlayerProfile p = new PlayerProfile(uuid, rs.getString("last_name"));
        p.setEloRating(rs.getDouble("elo_rating"));
        p.setRatingDeviation(rs.getDouble("rating_deviation"));
        p.setVolatility(rs.getDouble("volatility"));
        p.setSkillScore(rs.getDouble("skill_score"));
        p.setConfidence(rs.getDouble("confidence"));
        p.setTotalFights(rs.getInt("total_fights"));
        String tierName = rs.getString("tier");
        p.setTier(tierName != null ? Tier.valueOf(tierName) : null);
        p.setProvisional(rs.getInt("provisional") == 1);
        return p;
    }
}
