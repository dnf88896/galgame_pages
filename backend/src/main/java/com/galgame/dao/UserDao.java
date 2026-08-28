package com.galgame.dao;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.galgame.model.User;

/**
 * 用户数据访问层。
 */
@Repository
public class UserDao {

    private final JdbcTemplate jdbcTemplate;

    private static final RowMapper<User> USER_ROW_MAPPER = (ResultSet rs, int rowNum) ->
            new User(
                    rs.getLong("id"),
                    rs.getString("username"),
                    rs.getString("nickname"),
                    rs.getString("avatar_url"),
                    rs.getString("bio"),
                    rs.getTimestamp("created_at").toLocalDateTime(),
                    rs.getInt("admin_level"),
                    rs.getInt("hide_favorites"),
                    nullableTimestamp(rs, "ban_until"),
                    rs.getInt("moe_points"));

    public UserDao(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<User> findById(Long id) {
        List<User> rows = jdbcTemplate.query(
                "SELECT id, username, nickname, avatar_url, bio, admin_level, hide_favorites, ban_until, moe_points, created_at FROM users WHERE id = ?",
                USER_ROW_MAPPER, id);
        return rows.stream().findFirst();
    }

    public Optional<User> findByUsername(String username) {
        List<User> rows = jdbcTemplate.query(
                "SELECT id, username, nickname, avatar_url, bio, admin_level, hide_favorites, ban_until, moe_points, created_at FROM users WHERE username = ?",
                USER_ROW_MAPPER, username);
        return rows.stream().findFirst();
    }

    /** 批量按 id 查用户；空集合返回空列表 */
    public List<User> findByIds(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        StringBuilder sql = new StringBuilder(
                "SELECT id, username, nickname, avatar_url, bio, admin_level, hide_favorites, ban_until, moe_points, created_at FROM users WHERE id IN (");
        for (int i = 0; i < ids.size(); i++) {
            if (i > 0) {
                sql.append(", ");
            }
            sql.append("?");
        }
        sql.append(")");
        return jdbcTemplate.query(sql.toString(), USER_ROW_MAPPER, ids.toArray());
    }

    /** 全部用户 id（公告广播给所有用户用） */
    public List<Long> findAllUserIds() {
        return jdbcTemplate.query("SELECT id FROM users", (rs, rowNum) -> rs.getLong("id"));
    }

    public boolean existsByUsername(String username) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM users WHERE username = ?", Integer.class, username);
        return count != null && count > 0;
    }

    /** 取某用户名的密码哈希（登录校验用），用户不存在返回 empty */
    public Optional<String> findPasswordHashByUsername(String username) {
        List<String> rows = jdbcTemplate.query(
                "SELECT password_hash FROM users WHERE username = ?",
                (rs, rowNum) -> rs.getString("password_hash"), username);
        return rows.stream().findFirst();
    }

    /** 取某用户 id 的密码哈希（修改密码校验用），用户不存在返回 empty */
    public Optional<String> findPasswordHashById(Long id) {
        List<String> rows = jdbcTemplate.query(
                "SELECT password_hash FROM users WHERE id = ?",
                (rs, rowNum) -> rs.getString("password_hash"), id);
        return rows.stream().findFirst();
    }

    /** 更新密码哈希 */
    public void updatePassword(Long id, String passwordHash) {
        jdbcTemplate.update("UPDATE users SET password_hash = ? WHERE id = ?", passwordHash, id);
    }

    /** 新增用户，返回数据库生成的自增 id；昵称初始=账号名（可后续修改） */
    public Long insert(String username, String passwordHash) {
        String sql = "INSERT INTO users (username, nickname, password_hash) VALUES (?, ?, ?)";
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, username);
            ps.setString(2, username);
            ps.setString(3, passwordHash);
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    public void updateBio(Long id, String bio) {
        jdbcTemplate.update("UPDATE users SET bio = ? WHERE id = ?", bio, id);
    }

    /** 更新昵称（可重复，不做唯一校验；昵称为空/超长由接口层校验） */
    public void updateNickname(Long id, String nickname) {
        jdbcTemplate.update("UPDATE users SET nickname = ? WHERE id = ?", nickname, id);
    }

    /** 设置是否隐藏收藏列表：1 隐藏（他人不可见），0 公开（默认）。 */
    public void updateHideFavorites(Long id, int hideFavorites) {
        jdbcTemplate.update("UPDATE users SET hide_favorites = ? WHERE id = ?", hideFavorites, id);
    }

    public void updateAvatarUrl(Long id, String avatarUrl) {
        jdbcTemplate.update("UPDATE users SET avatar_url = ? WHERE id = ?", avatarUrl, id);
    }

    /** 提升管理员权限等级（只升不降：是否提升由调用方判断）。 */
    public void grantAdminLevel(Long id, int level) {
        jdbcTemplate.update("UPDATE users SET admin_level = ? WHERE id = ?", level, id);
    }

    /** 设置封禁截止时间（null=解封；否则为封禁到期时间，过期自动视为未封禁） */
    public void updateBanUntil(Long id, LocalDateTime until) {
        jdbcTemplate.update("UPDATE users SET ban_until = ? WHERE id = ?", until, id);
    }

    /** 增减萌点（未来「消耗萌点」等功能用）：delta 可为正/负，GREATEST 保证结果不为负 */
    public void adjustMoePoints(Long userId, int delta) {
        jdbcTemplate.update(
                "UPDATE users SET moe_points = GREATEST(0, moe_points + ?) WHERE id = ?", delta, userId);
    }

    /**
     * 每日奖励（签到/发帖/评论）：同一天同一类型只奖一次，数据库唯一键兜底并发安全。
     * 先 INSERT IGNORE 写 daily_rewards，affected==1 说明今日首次 → 加分并返回 true；否则返回 false（今日已奖励过）。
     */
    @Transactional
    public boolean claimDailyReward(Long userId, String actionType, int amount) {
        int affected = jdbcTemplate.update(
                "INSERT IGNORE INTO daily_rewards (user_id, action_type, action_date) VALUES (?, ?, CURRENT_DATE)",
                userId, actionType);
        if (affected == 1) {
            adjustMoePoints(userId, amount);
            return true;
        }
        return false;
    }

    /** 今日是否已领取过某类型奖励（签到状态查询用） */
    public boolean hasDailyReward(Long userId, String actionType) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM daily_rewards WHERE user_id = ? AND action_type = ? AND action_date = CURRENT_DATE",
                Integer.class, userId, actionType);
        return count != null && count > 0;
    }

    /** 按用户名/昵称模糊搜索（最多 50 条，新注册优先）；用户名只允许中英文/数字/下划线，无需 LIKE 转义 */
    public List<User> findByNameLike(String keyword) {
        return jdbcTemplate.query(
                "SELECT id, username, nickname, avatar_url, bio, admin_level, hide_favorites, ban_until, moe_points, created_at "
                        + "FROM users WHERE username LIKE ? OR nickname LIKE ? ORDER BY id DESC LIMIT 50",
                USER_ROW_MAPPER, "%" + keyword + "%", "%" + keyword + "%");
    }

    private static LocalDateTime nullableTimestamp(ResultSet rs, String column) throws SQLException {
        java.sql.Timestamp ts = rs.getTimestamp(column);
        return ts == null ? null : ts.toLocalDateTime();
    }
}
