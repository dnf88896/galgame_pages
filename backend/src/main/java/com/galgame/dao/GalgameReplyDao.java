package com.galgame.dao;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
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
import com.galgame.model.GalgameReply;
import com.galgame.model.LikeResult;

/**
 * Galgame 详情页评论数据访问层（仿 {@link ReplyDao}，去掉置顶）。
 */
@Repository
public class GalgameReplyDao {

    /**
     * 基础列：author 实时取评论者昵称（COALESCE(u.nickname, galgame_replies.author)），匿名/用户已删除回退快照列；
     * parent_author 实时取父评论作者昵称（父评论被删/匿名时回退快照列 parent_author）。
     * 查询须 LEFT JOIN users u、galgame_replies pr、users pu（见各方法 SQL）。
     */
    private static final String BASE_COLUMNS =
            "galgame_replies.id, galgame_replies.galgame_id, galgame_replies.user_id, "
                    + "COALESCE(u.nickname, galgame_replies.author) AS author, "
                    + "galgame_replies.content, galgame_replies.is_long, galgame_replies.images, galgame_replies.created_at, galgame_replies.like_count, galgame_replies.dislike_count, "
                    + "galgame_replies.parent_id, "
                    + "COALESCE(pu.nickname, galgame_replies.parent_author) AS parent_author";

    private final JdbcTemplate jdbcTemplate;

    /** Jackson 3 ObjectMapper：RowMapper 里把 galgame_replies.images 的 JSON 数组文本反序列化成 List<String> */
    private static final ObjectMapper JSON = new ObjectMapper();

    private static final RowMapper<GalgameReply> GALGAME_REPLY_ROW_MAPPER = (ResultSet rs, int rowNum) ->
            GalgameReply.core(
                    rs.getLong("id"),
                    rs.getLong("galgame_id"),
                    nullableLong(rs, "user_id"),
                    nullableLong(rs, "parent_id"),
                    nullableString(rs, "parent_author"),
                    rs.getString("author"),
                    rs.getString("content"),
                    rs.getBoolean("is_long"),
                    parseImages(rs.getString("images")),
                    rs.getTimestamp("created_at").toLocalDateTime(),
                    rs.getInt("like_count"),
                    rs.getInt("dislike_count"));

    public GalgameReplyDao(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 某 galgame 的全部评论，按时间升序（平铺，无置顶排序） */
    public List<GalgameReply> findByGalgameId(Long galgameId) {
        return jdbcTemplate.query(
                "SELECT " + BASE_COLUMNS
                        + " FROM galgame_replies"
                        + " LEFT JOIN users u ON u.id = galgame_replies.user_id"
                        + " LEFT JOIN galgame_replies pr ON pr.id = galgame_replies.parent_id"
                        + " LEFT JOIN users pu ON pu.id = pr.user_id"
                        + " WHERE galgame_replies.galgame_id = ? ORDER BY galgame_replies.created_at ASC, galgame_replies.id ASC",
                GALGAME_REPLY_ROW_MAPPER, galgameId);
    }

    public Optional<GalgameReply> findById(Long id) {
        List<GalgameReply> rows = jdbcTemplate.query(
                "SELECT " + BASE_COLUMNS
                        + " FROM galgame_replies"
                        + " LEFT JOIN users u ON u.id = galgame_replies.user_id"
                        + " LEFT JOIN galgame_replies pr ON pr.id = galgame_replies.parent_id"
                        + " LEFT JOIN users pu ON pu.id = pr.user_id"
                        + " WHERE galgame_replies.id = ?",
                GALGAME_REPLY_ROW_MAPPER, id);
        return rows.stream().findFirst();
    }

    public boolean existsById(Long id) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM galgame_replies WHERE id = ?", Integer.class, id);
        return count != null && count > 0;
    }

    /** 新增评论，返回数据库生成的自增 id；isLong 长短评标记（true=长评）；imagesJson 为图片 URL 的 JSON 数组文本（无图片传 null） */
    public Long insert(Long galgameId, String author, String content, boolean isLong, String imagesJson, Long userId, Long parentId, String parentAuthor) {
        String sql = "INSERT INTO galgame_replies (galgame_id, author, content, is_long, images, user_id, parent_id, parent_author) VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, galgameId);
            ps.setString(2, author);
            ps.setString(3, content);
            ps.setBoolean(4, isLong);
            if (imagesJson == null) {
                ps.setNull(5, java.sql.Types.VARCHAR);
            } else {
                ps.setString(5, imagesJson);
            }
            if (userId == null) {
                ps.setNull(6, java.sql.Types.BIGINT);
            } else {
                ps.setLong(6, userId);
            }
            if (parentId == null) {
                ps.setNull(7, java.sql.Types.BIGINT);
            } else {
                ps.setLong(7, parentId);
            }
            // 嵌套评论时快照父作者名；父评论被删后子评论仍能显示「回复 @xx」
            if (parentAuthor == null) {
                ps.setNull(8, java.sql.Types.VARCHAR);
            } else {
                ps.setString(8, parentAuthor);
            }
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    /** 批量查询这些用户对某 galgame 的评分，返回 user_id → score 映射（无评分/score 为 NULL 的用户不在结果里） */
    public Map<Long, Double> findRatingsByUsers(Long galgameId, Collection<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return Map.of();
        }
        String inClause = userIds.stream().map(String::valueOf).collect(Collectors.joining(","));
        String sql = "SELECT user_id, score FROM galgame_ratings "
                + "WHERE galgame_id = ? AND score IS NOT NULL AND user_id IN (" + inClause + ")";
        Map<Long, Double> result = new HashMap<>();
        jdbcTemplate.query(sql, rs -> {
            Long uid = nullableLong(rs, "user_id");
            Double score = nullableDouble(rs, "score");
            if (uid != null && score != null) {
                result.put(uid, score);
            }
        }, galgameId);
        return result;
    }

    /** 批量查询某用户赞过哪些 Galgame 评论，返回评论 id 集合（防 N+1） */
    public Set<Long> findLikedReplyIds(List<Long> replyIds, Long userId) {
        if (replyIds == null || replyIds.isEmpty()) {
            return Set.of();
        }
        String inClause = replyIds.stream().map(String::valueOf).collect(Collectors.joining(","));
        String sql = "SELECT reply_id FROM galgame_reply_likes WHERE reply_id IN (" + inClause + ") AND user_id = ?";
        return new HashSet<>(jdbcTemplate.query(sql, (rs, rowNum) -> rs.getLong("reply_id"), userId));
    }

    /** 批量查询某用户踩过哪些 Galgame 评论，返回评论 id 集合（防 N+1） */
    public Set<Long> findDislikedReplyIds(List<Long> replyIds, Long userId) {
        if (replyIds == null || replyIds.isEmpty()) {
            return Set.of();
        }
        String inClause = replyIds.stream().map(String::valueOf).collect(Collectors.joining(","));
        String sql = "SELECT reply_id FROM galgame_reply_dislikes WHERE reply_id IN (" + inClause + ") AND user_id = ?";
        return new HashSet<>(jdbcTemplate.query(sql, (rs, rowNum) -> rs.getLong("reply_id"), userId));
    }

    /**
     * 评论点踩 toggle。已踩则取消，未踩则点踩，并同步 galgame_replies.dislike_count。
     * 与点赞完全独立（不互斥）；整个操作在一个事务里；评论不存在时返回 empty。
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
                    "DELETE FROM galgame_reply_dislikes WHERE reply_id = ? AND user_id = ?", replyId, userId);
            jdbcTemplate.update(
                    "UPDATE galgame_replies SET dislike_count = GREATEST(0, dislike_count - 1) WHERE id = ?", replyId);
            disliked = false;
        } else {
            jdbcTemplate.update(
                    "INSERT INTO galgame_reply_dislikes (reply_id, user_id) VALUES (?, ?)", replyId, userId);
            jdbcTemplate.update(
                    "UPDATE galgame_replies SET dislike_count = dislike_count + 1 WHERE id = ?", replyId);
            disliked = true;
        }
        Integer count = jdbcTemplate.queryForObject(
                "SELECT dislike_count FROM galgame_replies WHERE id = ?", Integer.class, replyId);
        return Optional.of(new LikeResult(disliked, count == null ? 0 : count));
    }

    /**
     * 评论点赞 toggle。已赞则取消，未赞则点赞，并同步 galgame_replies.like_count。
     * 整个操作在一个事务里完成；评论不存在时返回 empty。
     */
    @Transactional
    public Optional<LikeResult> toggleLike(Long replyId, Long userId) {
        if (!existsById(replyId)) {
            return Optional.empty();
        }
        boolean liked;
        if (isLiked(replyId, userId)) {
            jdbcTemplate.update(
                    "DELETE FROM galgame_reply_likes WHERE reply_id = ? AND user_id = ?", replyId, userId);
            jdbcTemplate.update(
                    "UPDATE galgame_replies SET like_count = GREATEST(0, like_count - 1) WHERE id = ?", replyId);
            liked = false;
        } else {
            jdbcTemplate.update(
                    "INSERT INTO galgame_reply_likes (reply_id, user_id) VALUES (?, ?)", replyId, userId);
            jdbcTemplate.update(
                    "UPDATE galgame_replies SET like_count = like_count + 1 WHERE id = ?", replyId);
            liked = true;
        }
        Integer count = jdbcTemplate.queryForObject(
                "SELECT like_count FROM galgame_replies WHERE id = ?", Integer.class, replyId);
        return Optional.of(new LikeResult(liked, count == null ? 0 : count));
    }

    /** 删除评论。子评论的 parent_id 由外键 ON DELETE SET NULL 自动置空；赞/踩由外键级联删除。 */
    public int deleteById(Long id) {
        return jdbcTemplate.update("DELETE FROM galgame_replies WHERE id = ?", id);
    }

    private boolean isDisliked(Long replyId, Long userId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM galgame_reply_dislikes WHERE reply_id = ? AND user_id = ?",
                Integer.class, replyId, userId);
        return count != null && count > 0;
    }

    private boolean isLiked(Long replyId, Long userId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM galgame_reply_likes WHERE reply_id = ? AND user_id = ?",
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

    private static Double nullableDouble(ResultSet rs, String column) throws SQLException {
        double value = rs.getDouble(column);
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
