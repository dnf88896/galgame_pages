package com.galgame.dao;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import com.galgame.model.Notification;

/**
 * 消息通知数据访问层，对应 notifications 表。
 */
@Repository
public class NotificationDao {

    private static final String BASE_COLUMNS =
            "id, user_id, type, actor_id, post_id, reply_id, title, content, is_read, created_at";

    private final JdbcTemplate jdbcTemplate;

    private static final RowMapper<Notification> NOTIFICATION_ROW_MAPPER = (ResultSet rs, int rowNum) ->
            new Notification(
                    rs.getLong("id"),
                    rs.getLong("user_id"),
                    rs.getString("type"),
                    nullableLong(rs, "actor_id"),
                    nullableLong(rs, "post_id"),
                    nullableLong(rs, "reply_id"),
                    rs.getString("title"),
                    rs.getString("content"),
                    rs.getBoolean("is_read"),
                    rs.getTimestamp("created_at").toLocalDateTime());

    public NotificationDao(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 新增通知，返回数据库生成的自增 id */
    public Long insert(long userId, String type, Long actorId, Long postId, Long replyId,
                       String title, String content) {
        String sql = "INSERT INTO notifications (user_id, type, actor_id, post_id, reply_id, title, content) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?)";
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, userId);
            ps.setString(2, type);
            setNullableLong(ps, 3, actorId);
            setNullableLong(ps, 4, postId);
            setNullableLong(ps, 5, replyId);
            setNullableString(ps, 6, title);
            setNullableString(ps, 7, content);
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    /** 某用户的最近通知，按 id 倒序，最多 limit 条 */
    public List<Notification> findByUserId(long userId, int limit) {
        return jdbcTemplate.query(
                "SELECT " + BASE_COLUMNS + " FROM notifications WHERE user_id = ? "
                        + "ORDER BY id DESC LIMIT ?",
                NOTIFICATION_ROW_MAPPER, userId, limit);
    }

    /** 某用户的未读通知数 */
    public int countUnread(long userId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM notifications WHERE user_id = ? AND is_read = 0",
                Integer.class, userId);
        return count == null ? 0 : count;
    }

    /** 把某用户的全部未读通知标为已读，返回影响行数 */
    public int markAllRead(long userId) {
        return jdbcTemplate.update(
                "UPDATE notifications SET is_read = 1 WHERE user_id = ? AND is_read = 0", userId);
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static void setNullableLong(PreparedStatement ps, int index, Long value) throws SQLException {
        if (value == null) {
            ps.setNull(index, java.sql.Types.BIGINT);
        } else {
            ps.setLong(index, value);
        }
    }

    private static void setNullableString(PreparedStatement ps, int index, String value) throws SQLException {
        if (value == null) {
            ps.setNull(index, java.sql.Types.VARCHAR);
        } else {
            ps.setString(index, value);
        }
    }
}
