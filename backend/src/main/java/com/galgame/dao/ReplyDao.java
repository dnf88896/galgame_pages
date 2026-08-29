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

import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import com.galgame.model.LikeResult;
import com.galgame.model.Reply;

/**
 * 回复数据访问层。
 */
@Repository
public class ReplyDao {

    /**
     * 基础列：author 实时取回复者昵称（COALESCE(u.nickname, replies.author)），匿名/用户已删除回退快照列；
     * parent_author 实时取父回复作者昵称（父回复被删/匿名时回退快照列 parent_author）。
     * 查询须 LEFT JOIN users u、replies pr、users pu（见各方法 SQL）。
     */
    private static final String BASE_COLUMNS =
            "replies.id, replies.post_id, replies.user_id, "
                    + "COALESCE(u.nickname, replies.author) AS author, "
                    + "replies.content, replies.images, replies.created_at, replies.like_count, replies.dislike_count, "
                    + "replies.parent_id, "
                    + "COALESCE(pu.nickname, replies.parent_author) AS parent_author, "
                    + "replies.is_pinned";

    private final JdbcTemplate jdbcTemplate;

    /** Jackson 3 ObjectMapper：RowMapper 里把 replies.images 的 JSON 数组文本反序列化成 List<String> */
    private static final ObjectMapper JSON = new ObjectMapper();

    private static final RowMapper<Reply> REPLY_ROW_MAPPER = (ResultSet rs, int rowNum) ->
            Reply.core(
                    rs.getLong("id"),
                    nullableLong(rs, "user_id"),
                    rs.getLong("post_id"),
                    nullableLong(rs, "parent_id"),
                    nullableString(rs, "parent_author"),
                    rs.getString("author"),
                    rs.getString("content"),
                    parseImages(rs.getString("images")),
                    rs.getTimestamp("created_at").toLocalDateTime(),
                    rs.getInt("like_count"),
                    rs.getInt("dislike_count"),
                    rs.getBoolean("is_pinned"));

    public ReplyDao(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 某帖子的全部回复，按时间升序 */
    public List<Reply> findByPostId(Long postId) {
        return jdbcTemplate.query(
                "SELECT " + BASE_COLUMNS
                        + " FROM replies"
                        + " LEFT JOIN users u ON u.id = replies.user_id"
                        + " LEFT JOIN replies pr ON pr.id = replies.parent_id"
                        + " LEFT JOIN users pu ON pu.id = pr.user_id"
                        + " WHERE replies.post_id = ? ORDER BY replies.is_pinned DESC, replies.created_at ASC, replies.id ASC",
                REPLY_ROW_MAPPER, postId);
    }

    public Optional<Reply> findById(Long id) {
        List<Reply> rows = jdbcTemplate.query(
                "SELECT " + BASE_COLUMNS
                        + " FROM replies"
                        + " LEFT JOIN users u ON u.id = replies.user_id"
                        + " LEFT JOIN replies pr ON pr.id = replies.parent_id"
                        + " LEFT JOIN users pu ON pu.id = pr.user_id"
                        + " WHERE replies.id = ? ORDER BY replies.is_pinned DESC, replies.created_at ASC, replies.id ASC",
                REPLY_ROW_MAPPER, id);
        return rows.stream().findFirst();
    }

    public boolean existsById(Long id) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM replies WHERE id = ?", Integer.class, id);
        return count != null && count > 0;
    }

    /** 设置评论置顶（发帖人/管理员）：无时间限制，pinned 为 false 即取消置顶 */
    public void setPinned(Long replyId, boolean pinned) {
        jdbcTemplate.update("UPDATE replies SET is_pinned = ? WHERE id = ?", pinned, replyId);
    }

    /** 新增回复，返回数据库生成的自增 id；imagesJson 为图片 URL 的 JSON 数组文本（无图片传 null） */
    public Long insert(Long postId, String author, String content, String imagesJson, Long userId, Long parentId, String parentAuthor) {
        String sql = "INSERT INTO replies (post_id, author, content, images, user_id, parent_id, parent_author) VALUES (?, ?, ?, ?, ?, ?, ?)";
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, postId);
            ps.setString(2, author);
            ps.setString(3, content);
            if (imagesJson == null) {
                ps.setNull(4, java.sql.Types.VARCHAR);
            } else {
                ps.setString(4, imagesJson);
            }
            if (userId == null) {
                ps.setNull(5, java.sql.Types.BIGINT);
            } else {
                ps.setLong(5, userId);
            }
            if (parentId == null) {
                ps.setNull(6, java.sql.Types.BIGINT);
            } else {
                ps.setLong(6, parentId);
            }
            // 嵌套回复时快照父作者名；父评论被删后子回复仍能显示「回复 @xx」
            if (parentAuthor == null) {
                ps.setNull(7, java.sql.Types.VARCHAR);
            } else {
                ps.setString(7, parentAuthor);
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

    /** 批量查询某用户踩过哪些回复，返回回复 id 集合（防 N+1） */
    public Set<Long> findDislikedReplyIds(List<Long> replyIds, Long userId) {
        if (replyIds == null || replyIds.isEmpty()) {
            return Set.of();
        }
        String inClause = replyIds.stream().map(String::valueOf).collect(Collectors.joining(","));
        String sql = "SELECT reply_id FROM reply_dislikes WHERE reply_id IN (" + inClause + ") AND user_id = ?";
        return new HashSet<>(jdbcTemplate.query(sql, (rs, rowNum) -> rs.getLong("reply_id"), userId));
    }

    /** 判断某用户是否踩过该回复（与点赞独立，可同时赞和踩） */
    public boolean isDisliked(Long replyId, Long userId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM reply_dislikes WHERE reply_id = ? AND user_id = ?",
                Integer.class, replyId, userId);
        return count != null && count > 0;
    }

    /**
     * 回复点踩 toggle。已踩则取消，未踩则点踩，并同步 replies.dislike_count。
     * 与点赞完全独立（不互斥）；整个操作在一个事务里；回复不存在时返回 empty。
     * 返回值复用 LikeResult（liked 槽存「是否已踩」，likeCount 槽存「踩数」）。
     */
    @Transactional
    public Optional<LikeResult> toggleDislike(Long replyId, Long userId) {
        if (!existsById(replyId)) {
            return Optional.empty();
        }
        boolean disliked;
        if (isDisliked(replyId, userId)) {
            jdbcTemplate.update(
                    "DELETE FROM reply_dislikes WHERE reply_id = ? AND user_id = ?", replyId, userId);
            jdbcTemplate.update(
                    "UPDATE replies SET dislike_count = GREATEST(0, dislike_count - 1) WHERE id = ?", replyId);
            disliked = false;
        } else {
            jdbcTemplate.update(
                    "INSERT INTO reply_dislikes (reply_id, user_id) VALUES (?, ?)", replyId, userId);
            jdbcTemplate.update(
                    "UPDATE replies SET dislike_count = dislike_count + 1 WHERE id = ?", replyId);
            disliked = true;
        }
        Integer count = jdbcTemplate.queryForObject(
                "SELECT dislike_count FROM replies WHERE id = ?", Integer.class, replyId);
        return Optional.of(new LikeResult(disliked, count == null ? 0 : count));
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

    /** DB 里 images 列存的 JSON 数组文本（如 ["/uploads/.../a.png"]）→ List<String>；null/空/解析失败 → null（序列化时省略） */
    private static List<String> parseImages(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            List<String> images = JSON.readValue(json, new TypeReference<List<String>>() {
            });
            return (images == null || images.isEmpty()) ? null : images;
        } catch (JacksonException e) {
            return null;
        }
    }
}
