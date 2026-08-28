package com.galgame.dao;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.galgame.model.LikeResult;
import com.galgame.model.Post;
import com.galgame.model.PostFilter;
import com.galgame.model.RecentPost;

/**
 * 帖子数据访问层。使用 JdbcTemplate（底层走 JDBC）访问 MySQL。
 * <p>标签不再存 posts.section 单值列，改由 post_tags 多对多关联表承载：
 * 查询出基础行后再批量挂载 tags，写库时通过 insertTags 批量插入。
 */
@Repository
public class PostDao {

    /**
     * 基础列：author 实时取用户昵称（COALESCE(u.nickname, posts.author)），匿名帖（user_id 为 NULL）
     * 或用户已删除时回退快照列 author。所有查询必须 LEFT JOIN users u（见各方法 SQL）。
     */
    private static final String BASE_COLUMNS =
            "posts.id, posts.user_id, COALESCE(u.nickname, posts.author) AS author, "
                    + "posts.category, posts.title, posts.content, posts.created_at, "
                    + "posts.reply_count, posts.view_count, posts.like_count, "
                    + "posts.dislike_count, posts.cover_image, posts.pinned_until";

    private final JdbcTemplate jdbcTemplate;

    private static final RowMapper<Post> POST_ROW_MAPPER = (ResultSet rs, int rowNum) ->
            Post.core(
                    rs.getLong("id"),
                    nullableLong(rs, "user_id"),
                    rs.getString("author"),
                    rs.getString("category"),
                    List.of(),
                    rs.getString("title"),
                    rs.getString("content"),
                    rs.getTimestamp("created_at").toLocalDateTime(),
                    rs.getInt("reply_count"),
                    rs.getInt("view_count"),
                    rs.getInt("like_count"),
                    rs.getInt("dislike_count"),
                    nullableString(rs, "cover_image"),
                    nullableLocalDateTime(rs, "pinned_until"));

    private static final RowMapper<RecentPost> RECENT_POST_MAPPER = (ResultSet rs, int rowNum) ->
            new RecentPost(rs.getLong("id"), rs.getString("title"),
                    rs.getTimestamp("created_at").toLocalDateTime(),
                    nullableLocalDateTime(rs, "pinned_until"));

    public PostDao(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 帖子列表。按过滤条件动态拼接 WHERE：q 做关键词模糊匹配，category 做分区精确过滤，
     * 均为空则返回全部。sections 支持多标签 AND 过滤（?sections=a,b,c 或重复参数）：
     * 每个标签通过 post_tags 关联表一条 EXISTS 判断，一帖须同时拥有所有指定标签（一帖可挂多个标签）。
     * hiddenAuthorIds 为对当前查看者不可见的作者集合（屏蔽是双向的），
     * 非空时追加 (user_id IS NULL OR user_id NOT IN (...)) 条件；user_id 为 NULL 的旧数据始终可见。
     * sort 为 following 时只返回当前用户关注的人的帖子（currentUserId 为空则返回空列表）。
     * 排序：time 按时间倒序；hot 按热度（view + reply*3 + like*5）倒序；likes 按点赞量倒序；其余默认时间倒序。
     */
    public List<Post> findAll(PostFilter filter, Collection<Long> hiddenAuthorIds, Long currentUserId) {
        String sort = filter == null ? null : filter.sort();
        boolean followingOnly = "following".equals(sort);
        if (followingOnly && currentUserId == null) {
            return List.of();
        }
        StringBuilder sql = new StringBuilder(
                "SELECT " + BASE_COLUMNS + " FROM posts LEFT JOIN users u ON u.id = posts.user_id");
        List<String> where = new ArrayList<>();
        List<Object> args = new ArrayList<>();
        if (followingOnly) {
            where.add("posts.user_id IN (SELECT following_id FROM follows WHERE follower_id = ?)");
            args.add(currentUserId);
        }
        if (filter != null) {
            if (filter.category() != null) {
                where.add("posts.category = ?");
                args.add(filter.category());
            }
            if (filter.sections() != null) {
                for (String s : filter.sections()) {
                    where.add("EXISTS (SELECT 1 FROM post_tags pt WHERE pt.post_id = posts.id AND pt.section_key = ?)");
                    args.add(s);
                }
            }
            if (filter.q() != null) {
                where.add("(posts.title LIKE ? OR posts.content LIKE ? "
                        + "OR COALESCE(u.nickname, posts.author) LIKE ? OR posts.category LIKE ?)");
                String like = "%" + filter.q() + "%";
                args.add(like);
                args.add(like);
                args.add(like);
                args.add(like);
            }
        }
        if (hiddenAuthorIds != null && !hiddenAuthorIds.isEmpty()) {
            StringBuilder cond = new StringBuilder("(posts.user_id IS NULL OR posts.user_id NOT IN (");
            List<Long> ids = new ArrayList<>(hiddenAuthorIds);
            for (int i = 0; i < ids.size(); i++) {
                if (i > 0) {
                    cond.append(", ");
                }
                cond.append("?");
            }
            cond.append("))");
            where.add(cond.toString());
            args.addAll(ids);
        }
        if (!where.isEmpty()) {
            sql.append(" WHERE ").append(String.join(" AND ", where));
        }
        sql.append(orderBy(sort));
        List<Post> posts = new ArrayList<>(jdbcTemplate.query(sql.toString(), POST_ROW_MAPPER, args.toArray()));
        attachTags(posts);
        return posts;
    }

    /** 按排序值生成 ORDER BY 子句（含前置空格）；置顶（未过期）始终排最前；未知值回退时间倒序 */
    private static String orderBy(String sort) {
        String pinnedPrefix = "(posts.pinned_until IS NOT NULL AND posts.pinned_until > NOW()) DESC, ";
        if ("likes".equals(sort)) {
            return " ORDER BY " + pinnedPrefix + "posts.like_count DESC, posts.created_at DESC, posts.id DESC";
        }
        if ("hot".equals(sort)) {
            return " ORDER BY " + pinnedPrefix
                    + "(posts.view_count + posts.reply_count * 3 + posts.like_count * 5) DESC, "
                    + "posts.created_at DESC, posts.id DESC";
        }
        return " ORDER BY " + pinnedPrefix + "posts.created_at DESC, posts.id DESC";
    }

    /** 各分区帖子数，返回 [{name, count}]，供首页左侧分区栏展示 */
    public List<Map<String, Object>> countByCategory() {
        return jdbcTemplate.query(
                "SELECT category AS name, COUNT(*) AS count FROM posts GROUP BY category",
                (rs, i) -> Map.of("name", rs.getString("name"), "count", rs.getLong("count")));
    }

    public Optional<Post> findById(Long id) {
        List<Post> rows = new ArrayList<>(jdbcTemplate.query(
                "SELECT " + BASE_COLUMNS + " FROM posts LEFT JOIN users u ON u.id = posts.user_id "
                        + "WHERE posts.id = ?",
                POST_ROW_MAPPER, id));
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        attachTags(rows);
        return Optional.of(rows.get(0));
    }

    public boolean existsById(Long id) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM posts WHERE id = ?", Integer.class, id);
        return count != null && count > 0;
    }

    /** 修改帖子分区（管理员操作） */
    public void updateCategory(Long postId, String category) {
        jdbcTemplate.update("UPDATE posts SET category = ? WHERE id = ?", category, postId);
    }

    /** 新增帖子（含 tags），返回数据库生成的自增 id；写 posts 与 post_tags 在同一事务 */
    @Transactional
    public Long insert(String author, String category, String title, String content, Long userId, List<String> tags) {
        String sql = "INSERT INTO posts (author, category, title, content, user_id) "
                + "VALUES (?, ?, ?, ?, ?)";
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, author);
            ps.setString(2, category);
            ps.setString(3, title);
            ps.setString(4, content);
            if (userId == null) {
                ps.setNull(5, java.sql.Types.BIGINT);
            } else {
                ps.setLong(5, userId);
            }
            return ps;
        }, keyHolder);
        Long postId = keyHolder.getKey().longValue();
        insertTags(postId, tags);
        return postId;
    }

    /** 写入帖子封面图 URL（发帖保存封面后回填 cover_image 列） */
    public void updateCoverImage(Long postId, String coverImage) {
        jdbcTemplate.update("UPDATE posts SET cover_image = ? WHERE id = ?", coverImage, postId);
    }

    /** 设置帖子置顶截止时间（管理员）：until 为 null 即取消置顶 */
    public void setPinnedUntil(Long postId, LocalDateTime until) {
        jdbcTemplate.update("UPDATE posts SET pinned_until = ? WHERE id = ?", until, postId);
    }

    /** 批量写入帖子标签：过滤 null / 空字符串；tags 为空则跳过 */
    private void insertTags(Long postId, List<String> tags) {
        if (postId == null || tags == null || tags.isEmpty()) {
            return;
        }
        List<String> cleaned = tags.stream()
                .filter(t -> t != null && !t.isBlank())
                .map(String::trim)
                .toList();
        if (cleaned.isEmpty()) {
            return;
        }
        jdbcTemplate.batchUpdate(
                "INSERT INTO post_tags (post_id, section_key) VALUES (?, ?)",
                cleaned, cleaned.size(), (ps, tag) -> {
                    ps.setLong(1, postId);
                    ps.setString(2, tag);
                });
    }

    /** 批量加载一批帖子的 tags 并挂到每个 Post 上（无标签的帖子用空数组） */
    private void attachTags(List<Post> posts) {
        if (posts == null || posts.isEmpty()) {
            return;
        }
        Map<Long, List<String>> tagsByPost = loadTags(posts.stream().map(Post::id).toList());
        for (int i = 0; i < posts.size(); i++) {
            posts.set(i, posts.get(i).withTags(tagsByPost.getOrDefault(posts.get(i).id(), List.of())));
        }
    }

    /** 按 post_id 批量查标签，组装成 Map<Long, List<String>>（按 post_id 有序） */
    private Map<Long, List<String>> loadTags(Collection<Long> postIds) {
        Map<Long, List<String>> result = new LinkedHashMap<>();
        if (postIds == null || postIds.isEmpty()) {
            return result;
        }
        StringBuilder sql = new StringBuilder(
                "SELECT post_id, section_key FROM post_tags WHERE post_id IN (");
        for (int i = 0; i < postIds.size(); i++) {
            if (i > 0) {
                sql.append(", ");
            }
            sql.append("?");
        }
        sql.append(") ORDER BY post_id");
        jdbcTemplate.query(sql.toString(), rs -> {
            long pid = rs.getLong("post_id");
            result.computeIfAbsent(pid, k -> new ArrayList<>()).add(rs.getString("section_key"));
        }, postIds.toArray());
        return result;
    }

    public void incrementView(Long id) {
        jdbcTemplate.update("UPDATE posts SET view_count = view_count + 1 WHERE id = ?", id);
    }

    public void incrementReplyCount(Long id) {
        jdbcTemplate.update("UPDATE posts SET reply_count = reply_count + 1 WHERE id = ?", id);
    }

    /** 删除回复后递减帖子回复数（GREATEST 防负） */
    public void decrementReplyCount(Long id) {
        jdbcTemplate.update("UPDATE posts SET reply_count = GREATEST(0, reply_count - 1) WHERE id = ?", id);
    }

    /** 判断某用户是否赞过该帖子 */
    public boolean isLiked(Long postId, Long userId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM post_likes WHERE post_id = ? AND user_id = ?",
                Integer.class, postId, userId);
        return count != null && count > 0;
    }

    /** 判断某用户是否踩过该帖子（与点赞独立，可同时赞和踩） */
    public boolean isDisliked(Long postId, Long userId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM post_dislikes WHERE post_id = ? AND user_id = ?",
                Integer.class, postId, userId);
        return count != null && count > 0;
    }

    /**
     * 帖子点踩 toggle。已踩则取消，未踩则点踩，并同步 posts.dislike_count。
     * 与点赞完全独立（不互斥）；整个操作在一个事务里；帖子不存在时返回 empty。
     * 返回值复用 LikeResult（liked 槽存「是否已踩」，likeCount 槽存「踩数」）。
     */
    @Transactional
    public Optional<LikeResult> toggleDislike(Long postId, Long userId) {
        if (!existsById(postId)) {
            return Optional.empty();
        }
        boolean disliked;
        if (isDisliked(postId, userId)) {
            jdbcTemplate.update(
                    "DELETE FROM post_dislikes WHERE post_id = ? AND user_id = ?", postId, userId);
            jdbcTemplate.update(
                    "UPDATE posts SET dislike_count = GREATEST(0, dislike_count - 1) WHERE id = ?", postId);
            disliked = false;
        } else {
            jdbcTemplate.update(
                    "INSERT INTO post_dislikes (post_id, user_id) VALUES (?, ?)", postId, userId);
            jdbcTemplate.update(
                    "UPDATE posts SET dislike_count = dislike_count + 1 WHERE id = ?", postId);
            disliked = true;
        }
        Integer count = jdbcTemplate.queryForObject(
                "SELECT dislike_count FROM posts WHERE id = ?", Integer.class, postId);
        return Optional.of(new LikeResult(disliked, count == null ? 0 : count));
    }

    /** 判断某用户是否收藏过该帖子 */
    public boolean isFavorited(Long postId, Long userId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM post_favorites WHERE post_id = ? AND user_id = ?",
                Integer.class, postId, userId);
        return count != null && count > 0;
    }

    /**
     * 帖子点赞 toggle。已赞则取消，未赞则点赞，并同步 posts.like_count。
     * 整个操作在一个事务里完成；帖子不存在时返回 empty。
     */
    @Transactional
    public Optional<LikeResult> toggleLike(Long postId, Long userId) {
        if (!existsById(postId)) {
            return Optional.empty();
        }
        boolean liked;
        if (isLiked(postId, userId)) {
            jdbcTemplate.update(
                    "DELETE FROM post_likes WHERE post_id = ? AND user_id = ?", postId, userId);
            jdbcTemplate.update(
                    "UPDATE posts SET like_count = GREATEST(0, like_count - 1) WHERE id = ?", postId);
            liked = false;
        } else {
            jdbcTemplate.update(
                    "INSERT INTO post_likes (post_id, user_id) VALUES (?, ?)", postId, userId);
            jdbcTemplate.update(
                    "UPDATE posts SET like_count = like_count + 1 WHERE id = ?", postId);
            liked = true;
        }
        Integer count = jdbcTemplate.queryForObject(
                "SELECT like_count FROM posts WHERE id = ?", Integer.class, postId);
        return Optional.of(new LikeResult(liked, count == null ? 0 : count));
    }

    /**
     * 帖子收藏 toggle。已收藏则取消收藏，未收藏则收藏。
     * 收藏无计数，无需同步 posts 表；整个操作在一个事务里完成；帖子不存在时返回 empty。
     */
    @Transactional
    public Optional<Boolean> toggleFavorite(Long postId, Long userId) {
        if (!existsById(postId)) {
            return Optional.empty();
        }
        boolean favorited;
        if (isFavorited(postId, userId)) {
            jdbcTemplate.update(
                    "DELETE FROM post_favorites WHERE post_id = ? AND user_id = ?", postId, userId);
            favorited = false;
        } else {
            jdbcTemplate.update(
                    "INSERT INTO post_favorites (post_id, user_id) VALUES (?, ?)", postId, userId);
            favorited = true;
        }
        return Optional.of(favorited);
    }

    /**
     * 删除帖子。仅当该帖 user_id 与当前用户匹配时才删除（防删他人帖子）。
     * 回复/点赞/附件记录由外键 ON DELETE CASCADE 一并清除，附件文件由调用方清理。
     */
    @Transactional
    public boolean deleteByIdAndUser(Long postId, Long userId) {
        return jdbcTemplate.update(
                "DELETE FROM posts WHERE id = ? AND user_id = ?", postId, userId) > 0;
    }

    /** 无条件删除帖子（管理员处理举报用）；回复/点赞/附件/收藏/通知由外键级联，附件文件由调用方清理 */
    public boolean deleteById(Long postId) {
        return jdbcTemplate.update("DELETE FROM posts WHERE id = ?", postId) > 0;
    }

    // ── 用户公开资料统计 ─────────────────────

    /** 该用户的发帖数 */
    public int countByUserId(Long userId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM posts WHERE user_id = ?", Integer.class, userId);
        return count == null ? 0 : count;
    }

    /** 该用户所有帖子收到的赞总数 */
    public int countLikesReceived(Long userId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM post_likes l JOIN posts p ON l.post_id = p.id WHERE p.user_id = ?",
                Integer.class, userId);
        return count == null ? 0 : count;
    }

    /** 该用户收藏的帖子总数 */
    public int countFavorites(Long userId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM post_favorites WHERE user_id = ?", Integer.class, userId);
        return count == null ? 0 : count;
    }

    /**
     * 该用户收藏的帖子列表（按收藏时间倒序）。
     * 注意：JOIN 查询里 id/user_id/created_at 在 posts 与 post_favorites 两表中均存在，
     * 为避免歧义，基础列显式加了 p. 前缀（结果集列名不受影响，POST_ROW_MAPPER 仍按列名取值）。
     */
    public List<Post> findFavoritesByUser(Long userId) {
        String sql = "SELECT p.id, p.user_id, COALESCE(u.nickname, p.author) AS author, "
                + "p.category, p.title, p.content, p.created_at, p.reply_count, p.view_count, p.like_count, "
                + "p.dislike_count, p.cover_image, p.pinned_until "
                + "FROM post_favorites pf JOIN posts p ON p.id = pf.post_id "
                + "LEFT JOIN users u ON u.id = p.user_id "
                + "WHERE pf.user_id = ? "
                + "ORDER BY (p.pinned_until IS NOT NULL AND p.pinned_until > NOW()) DESC, pf.created_at DESC, p.id DESC";
        List<Post> posts = new ArrayList<>(jdbcTemplate.query(sql, POST_ROW_MAPPER, userId));
        attachTags(posts);
        return posts;
    }

    /** 该用户最新发布的 N 条帖子 */
    public List<RecentPost> findRecentPostsByUser(Long userId, int limit) {
        return jdbcTemplate.query(
                "SELECT id, title, created_at, pinned_until FROM posts WHERE user_id = ? "
                        + "ORDER BY created_at DESC, id DESC LIMIT ?",
                RECENT_POST_MAPPER, userId, limit);
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static String nullableString(ResultSet rs, String column) throws SQLException {
        String value = rs.getString(column);
        return rs.wasNull() ? null : value;
    }

    private static LocalDateTime nullableLocalDateTime(ResultSet rs, String column) throws SQLException {
        java.sql.Timestamp t = rs.getTimestamp(column);
        return t == null ? null : t.toLocalDateTime();
    }
}
