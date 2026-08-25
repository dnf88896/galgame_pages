package com.galgame.dao;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.galgame.model.LikeResult;
import com.galgame.model.Reply;

/**
 * 回复数据访问层。
 */
@Repository
public class ReplyDao {

    private static final String BASE_COLUMNS = "id, post_id, user_id, author, content, created_at, like_count, parent_id, parent_author";

    private final JdbcTemplate jdbcTemplate;

    private static final RowMapper<Reply> REPLY_ROW_MAPPER = (ResultSet rs, int rowNum) ->
            Reply.core(
                    rs.getLong("id"),
                    nullableLong(rs, "user_id"),
                    rs.getLong("post_id"),
                    nullableLong(rs, "parent_id"),
                    nullableString(rs, "parent_author"),
                    rs.getString("author"),
                    rs.getString("content"),
                    rs.getTimestamp("created_at").toLocalDateTime(),
                    rs.getInt("like_count"));

    public ReplyDao(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 某帖子的全部回复，按时间升序 */
    public List<Reply> findByPostId(Long postId) {
        return jdbcTemplate.query(
                "SELECT " + BASE_COLUMNS + " FROM replies WHERE post_id = ? ORDER BY created_at ASC, id ASC",
                REPLY_ROW_MAPPER, postId);
    }

    public Optional<Reply> findById(Long id) {
        List<Reply> rows = jdbcTemplate.query(
                "SELECT " + BASE_COLUMNS + " FROM replies WHERE id = ?", REPLY_ROW_MAPPER, id);
        return rows.stream().findFirst();
    }

    public boolean existsById(Long id) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM replies WHERE id = ?", Integer.class, id);
        return count != null && count > 0;
    }

    /** 新增回复，返回数据库生成的自增 id */
    public Long insert(Long postId, String author, String content, Long userId, Long parentId, String parentAuthor) {
        String sql = "INSERT INTO replies (post_id, author, content, user_id, parent_id, parent_author) VALUES (?, ?, ?, ?, ?, ?)";
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, postId);
            ps.setString(2, author);
            ps.setString(3, content);
            if (userId == null) {
                ps.setNull(4, java.sql.Types.BIGINT);
            } else {
                ps.setLong(4, userId);
            }
            if (parentId == null) {
                ps.setNull(5, java.sql.Types.BIGINT);
            } else {
                ps.setLong(5, parentId);
            }
            // 嵌套回复时快照父作者名；父评论被删后子回复仍能显示「回复 @xx」
            if (parentAuthor == null) {
                ps.setNull(6, java.sql.Types.VARCHAR);
            } else {
                ps.setString(6, parentAuthor);
            }
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    /** 批量查询某用户赞过哪些回复，返回回复 id 集合（防 N+1） */
    public Set<Long> findLikedReplyIds(List<Long> replyIds, Long userId) {
        if (replyIds == null || replyIds.isEmpty()) {
            return Set.of();
        }
        String inClause = replyIds.stream().map(String::valueOf).collect(Collectors.joining(","));
        String sql = "SELECT reply_id FROM reply_likes WHERE reply_id IN (" + inClause + ") AND user_id = ?";
        return new HashSet<>(jdbcTemplate.query(sql, (rs, rowNum) -> rs.getLong("reply_id"), userId));
    }

    /**
     * 回复点赞 toggle。已赞则取消，未赞则点赞，并同步 replies.like_count。
     * 整个操作在一个事务里完成；回复不存在时返回 empty。
     */
    @Transactional
    public Optional<LikeResult> toggleLike(Long replyId, Long userId) {
        if (!existsById(replyId)) {
            return Optional.empty();
        }
        boolean liked;
        if (isLiked(replyId, userId)) {
            jdbcTemplate.update(
                    "DELETE FROM reply_likes WHERE reply_id = ? AND user_id = ?", replyId, userId);
            jdbcTemplate.update(
                    "UPDATE replies SET like_count = GREATEST(0, like_count - 1) WHERE id = ?", replyId);
            liked = false;
        } else {
            jdbcTemplate.update(
                    "INSERT INTO reply_likes (reply_id, user_id) VALUES (?, ?)", replyId, userId);
            jdbcTemplate.update(
                    "UPDATE replies SET like_count = like_count + 1 WHERE id = ?", replyId);
            liked = true;
        }
        Integer count = jdbcTemplate.queryForObject(
                "SELECT like_count FROM replies WHERE id = ?", Integer.class, replyId);
        return Optional.of(new LikeResult(liked, count == null ? 0 : count));
    }

    /** 删除回复。子回复的 parent_id 由外键 ON DELETE SET NULL 自动置空；回复的赞/通知由外键级联删除。 */
    public int deleteById(Long id) {
        return jdbcTemplate.update("DELETE FROM replies WHERE id = ?", id);
    }

    // ── 用户公开资料统计 ─────────────────────

    /** 该用户的回复数 */
    public int countByUserId(Long userId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM replies WHERE user_id = ?", Integer.class, userId);
        return count == null ? 0 : count;
    }

    /** 该用户所有回复收到的赞总数 */
    public int countLikesReceived(Long userId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM reply_likes l JOIN replies r ON l.reply_id = r.id WHERE r.user_id = ?",
                Integer.class, userId);
        return count == null ? 0 : count;
    }

    private boolean isLiked(Long replyId, Long userId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM reply_likes WHERE reply_id = ? AND user_id = ?",
                Integer.class, replyId, userId);
        return count != null && count > 0;
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static String nullableString(ResultSet rs, String column) throws SQLException {
        String value = rs.getString(column);
        return rs.wasNull() ? null : value;
    }
}
