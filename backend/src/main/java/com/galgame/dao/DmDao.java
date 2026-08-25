package com.galgame.dao;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import com.galgame.model.DmMessage;

/**
 * 私聊（DM）数据访问层。使用 JdbcTemplate 访问 MySQL 的
 * dm_conversations / dm_messages 两张表。
 */
@Repository
public class DmDao {

    private static final String MESSAGE_COLUMNS =
            "id, conversation_id, sender_id, content, is_read, created_at";

    private final JdbcTemplate jdbcTemplate;

    private static final RowMapper<DmMessage> DM_MESSAGE_ROW_MAPPER = (ResultSet rs, int rowNum) ->
            new DmMessage(
                    rs.getLong("id"),
                    rs.getLong("sender_id"),
                    rs.getString("content"),
                    rs.getTimestamp("created_at").toLocalDateTime(),
                    rs.getBoolean("is_read"));

    /** 会话引用：conversationId 与对方用户 id */
    public record ConversationRef(Long conversationId, Long otherId) {
    }

    public DmDao(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 查找两个用户之间的会话，不存在则创建，返回会话 id。
     * id 较小的一方作为 user_low，较大的一方作为 user_high。
     */
    public Long getOrCreateConversation(long a, long b) {
        long low = Math.min(a, b);
        long high = Math.max(a, b);
        List<Long> existing = jdbcTemplate.query(
                "SELECT id FROM dm_conversations WHERE user_low = ? AND user_high = ?",
                (rs, rowNum) -> rs.getLong("id"), low, high);
        if (!existing.isEmpty()) {
            return existing.get(0);
        }
        String sql = "INSERT INTO dm_conversations (user_low, user_high) VALUES (?, ?)";
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, low);
            ps.setLong(2, high);
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    /** 新增消息，返回数据库生成的自增 id */
    public Long insertMessage(long conversationId, long senderId, String content) {
        String sql = "INSERT INTO dm_messages (conversation_id, sender_id, content) VALUES (?, ?, ?)";
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, conversationId);
            ps.setLong(2, senderId);
            ps.setString(3, content);
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    public Optional<DmMessage> findMessageById(long id) {
        List<DmMessage> rows = jdbcTemplate.query(
                "SELECT " + MESSAGE_COLUMNS + " FROM dm_messages WHERE id = ?",
                DM_MESSAGE_ROW_MAPPER, id);
        return rows.stream().findFirst();
    }

    /** 某会话的全部消息，按 id 升序 */
    public List<DmMessage> findMessages(long conversationId) {
        return jdbcTemplate.query(
                "SELECT " + MESSAGE_COLUMNS + " FROM dm_messages WHERE conversation_id = ? ORDER BY id ASC",
                DM_MESSAGE_ROW_MAPPER, conversationId);
    }

    /** 某会话的最后一条消息，无消息返回 empty */
    public Optional<DmMessage> findLastMessage(long conversationId) {
        List<DmMessage> rows = jdbcTemplate.query(
                "SELECT " + MESSAGE_COLUMNS + " FROM dm_messages WHERE conversation_id = ? ORDER BY id DESC LIMIT 1",
                DM_MESSAGE_ROW_MAPPER, conversationId);
        return rows.stream().findFirst();
    }

    /** 当前用户参与的所有会话，返回会话 id 与对方用户 id */
    public List<ConversationRef> findConversationsByUser(long userId) {
        return jdbcTemplate.query(
                "SELECT id, CASE WHEN user_low = ? THEN user_high ELSE user_low END AS other_id "
                        + "FROM dm_conversations WHERE user_low = ? OR user_high = ?",
                (rs, rowNum) -> new ConversationRef(rs.getLong("id"), rs.getLong("other_id")),
                userId, userId, userId);
    }

    /** 对方发给我、我还没读的消息数（sender 不是我） */
    public int countUnread(long conversationId, long viewerId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM dm_messages WHERE conversation_id = ? AND is_read = 0 AND sender_id <> ?",
                Integer.class, conversationId, viewerId);
        return count == null ? 0 : count;
    }

    /** 把该会话中「对方发给我」的未读消息标记为已读 */
    public void markRead(long conversationId, long viewerId) {
        jdbcTemplate.update(
                "UPDATE dm_messages SET is_read = 1 WHERE conversation_id = ? AND is_read = 0 AND sender_id <> ?",
                conversationId, viewerId);
    }
}
