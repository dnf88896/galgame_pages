package com.galgame.dao;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;
import com.galgame.model.Galgame;

/**
 * Galgame 作品库数据访问层。使用 JdbcTemplate 访问 MySQL。
 * <p>links 以 JSON 数组文本（[{label,url}]）存 galgames.links，读写用注入的 JsonMapper（Jackson 3）序列化/反序列化；
 * tags 走 galgame_tags 多对多关联表，查询时用 GROUP_CONCAT 子查询一次取回整行标签（按 section_key 排序）。
 * <p>评分：rating_avg / rating_count 存于 galgames，一人一票由防重表 galgame_ratings（主键 galgame_id+user_id）保证；
 * rate() 先插防重表，捕获 DuplicateKeyException（org.springframework.dao）判定已评过。
 */
@Repository
public class GalgameDao {

    private static final String BASE_COLUMNS =
            "g.id, g.name, g.description, g.image, g.staff, g.view_count, g.release_date, g.rating_avg, g.rating_count, g.links, g.created_by, g.created_at, g.updated_at, "
            + "g.status, g.reject_reason, g.reviewed_at, "
            + "(SELECT COALESCE(nickname, username) FROM users u WHERE u.id = g.created_by) AS creator, "
            + "(SELECT GROUP_CONCAT(section_key ORDER BY section_key) FROM galgame_tags gt "
            + "WHERE gt.galgame_id = g.id) AS tags";

    private final JdbcTemplate jdbcTemplate;
    private final JsonMapper objectMapper;
    private final RowMapper<Galgame> GALGAME_ROW_MAPPER;

    public GalgameDao(JdbcTemplate jdbcTemplate, JsonMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.GALGAME_ROW_MAPPER = (rs, rowNum) -> mapRow(rs);
    }

    /**
     * 新增 galgame（含 links JSON + tags），返回数据库生成的自增 id；
     * 主表与 galgame_tags 在同一事务。
     */
    @Transactional
    public Long insert(String name, String description, String image, String staff, LocalDate releaseDate,
                       List<Galgame.Link> links, List<String> tags, long createdBy, String status) {
        // Jackson 3 writeValueAsString 抛运行时异常，无需（也无法）捕获受检 IOException
        String linksJson = (links == null || links.isEmpty()) ? "[]" : objectMapper.writeValueAsString(links);
        String sql = "INSERT INTO galgames (name, description, image, staff, release_date, links, created_by, status) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, name);
            ps.setString(2, description);
            ps.setString(3, image);
            ps.setString(4, staff);
            ps.setDate(5, releaseDate == null ? null : java.sql.Date.valueOf(releaseDate));
            ps.setString(6, linksJson);
            ps.setLong(7, createdBy);
            ps.setString(8, status);
            return ps;
        }, keyHolder);
        Long galgameId = keyHolder.getKey().longValue();
        insertTags(galgameId, tags);
        return galgameId;
    }

    /**
     * 更新 galgame（含 links JSON + tags 全量替换）。主表 UPDATE 影响行数为 0 返回 false（调用方判 404）；
     * tags 先清空再按新值全量写入，与主表同事务；updated_at 由数据库 ON UPDATE CURRENT_TIMESTAMP 自动刷新。
     */
    @Transactional
    public boolean update(long id, String name, String description, String image, String staff, LocalDate releaseDate,
                          List<Galgame.Link> links, List<String> tags) {
        // Jackson 3 writeValueAsString 抛运行时异常，无需（也无法）捕获受检 IOException
        String linksJson = (links == null || links.isEmpty()) ? "[]" : objectMapper.writeValueAsString(links);
        int rows = jdbcTemplate.update(
                "UPDATE galgames SET name = ?, description = ?, image = ?, staff = ?, release_date = ?, links = ? WHERE id = ?",
                name, description, image, staff,
                releaseDate == null ? null : java.sql.Date.valueOf(releaseDate), linksJson, id);
        if (rows == 0) {
            return false;
        }
        jdbcTemplate.update("DELETE FROM galgame_tags WHERE galgame_id = ?", id);
        insertTags(id, tags);
        return true;
    }

    public Optional<Galgame> findById(long id) {
        List<Galgame> rows = jdbcTemplate.query(
                "SELECT " + BASE_COLUMNS + " FROM galgames g WHERE g.id = ?",
                GALGAME_ROW_MAPPER, id);
        return rows.stream().findFirst();
    }

    /**
     * Galgame 列表（公开，仅已上架）。固定过滤 status='approved'（pending/rejected 不进公开列表）；
     * 其余按条件动态拼接 WHERE：q 只按名称模糊匹配（作者/内容不搜）；
     * tags 为 gg-* 资源筛选标签，AND 语义：每标签一条 EXISTS 判断，
     * 一作须同时拥有所有指定标签；均无条件则返回全部。
     * sort 取值：created（默认）按创建时间倒序（最新在上）；
     * views/views_asc 按浏览数倒序/升序；rating/rating_asc 按评分从高到低/从低到高；
     * release_date_desc/release_date_asc 发售日期从新到旧/从旧到新
     * （无发售日期 / 未评分的排最后）。
     */
    public List<Galgame> findAll(String q, List<String> tags, String sort) {
        StringBuilder sql = new StringBuilder("SELECT " + BASE_COLUMNS + " FROM galgames g WHERE 1=1 AND g.status = 'approved'");
        List<Object> args = new ArrayList<>();
        if (q != null && !q.isBlank()) {
            sql.append(" AND g.name LIKE CONCAT('%',?,'%')");
            args.add(q.trim());
        }
        if (tags != null) {
            for (String t : tags) {
                if (t != null && !t.isBlank()) {
                    sql.append(" AND EXISTS (SELECT 1 FROM galgame_tags gt WHERE gt.galgame_id = g.id AND gt.section_key = ?)");
                    args.add(t.trim());
                }
            }
        }
        sql.append(orderBy(sort));
        return jdbcTemplate.query(sql.toString(), GALGAME_ROW_MAPPER, args.toArray());
    }

    /**
     * 按排序值生成 ORDER BY 子句（含前置空格）；未知值回退创建时间倒序。
     * rating / release_date 为 NULL（未评分 / 无发售日期）的一律排最后：
     * 用 (col IS NULL) 做首键（false=0 在 true=1 前）。方向由前端控制
     * （views/rating/release_date_desc 为倒序，*_asc 为升序）。
     */
    private static String orderBy(String sort) {
        if ("views".equals(sort)) {
            return " ORDER BY g.view_count DESC, g.created_at DESC, g.id DESC";
        }
        if ("views_asc".equals(sort)) {
            // 浏览数从低到高（view_count 非空默认 0，无需 NULL 处理）
            return " ORDER BY g.view_count ASC, g.created_at DESC, g.id DESC";
        }
        if ("rating".equals(sort)) {
            // 评分从高到低；同分按评分人数多者优先
            return " ORDER BY (g.rating_avg IS NULL), g.rating_avg DESC, g.rating_count DESC, g.created_at DESC, g.id DESC";
        }
        if ("rating_asc".equals(sort)) {
            // 评分从低到高；同分按评分人数少者优先
            return " ORDER BY (g.rating_avg IS NULL), g.rating_avg ASC, g.rating_count ASC, g.created_at DESC, g.id DESC";
        }
        if ("release_date_desc".equals(sort)) {
            // 发售日期从新到旧
            return " ORDER BY (g.release_date IS NULL), g.release_date DESC, g.created_at DESC, g.id DESC";
        }
        if ("release_date_asc".equals(sort)) {
            // 发售日期从旧到新
            return " ORDER BY (g.release_date IS NULL), g.release_date ASC, g.created_at DESC, g.id DESC";
        }
        return " ORDER BY g.created_at DESC, g.id DESC";
    }

    public boolean existsById(long id) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM galgames WHERE id = ?", Integer.class, id);
        return count != null && count > 0;
    }

    /** 待审核列表（管理员审核页）：仅 status='pending'，按提交时间倒序，含提交人昵称 creator */
    public List<Galgame> findPending() {
        return jdbcTemplate.query(
                "SELECT " + BASE_COLUMNS + " FROM galgames g WHERE g.status = 'pending' ORDER BY g.created_at DESC",
                GALGAME_ROW_MAPPER);
    }

    /** 「我的提交」：某个用户创建的全部 galgame（含各审核状态），按创建时间倒序（同秒按 id 倒序兜底），提交者回看/编辑入口 */
    public List<Galgame> findByCreator(long userId) {
        return jdbcTemplate.query(
                "SELECT " + BASE_COLUMNS + " FROM galgames g WHERE g.created_by = ? ORDER BY g.created_at DESC, g.id DESC",
                GALGAME_ROW_MAPPER, userId);
    }

    /** 审核更新：设置 status 与拒绝理由，reviewed_at 刷新为当前时间（轻量，不动 tags/links） */
    public boolean updateStatus(long id, String status, String rejectReason) {
        return jdbcTemplate.update(
                "UPDATE galgames SET status = ?, reject_reason = ?, reviewed_at = NOW() WHERE id = ?",
                status, rejectReason, id) > 0;
    }

    /** 审核通过萌点奖励防重：置位 moe_awarded=1，仅当原先为 0（未发放）时返回 true（原子，防并发/反复审核重复发放） */
    public boolean claimMoeAward(long id) {
        return jdbcTemplate.update(
                "UPDATE galgames SET moe_awarded = 1 WHERE id = ? AND moe_awarded = 0", id) > 0;
    }

    /** 浏览数 +1（详情页访问时调用） */
    public void incrementView(long id) {
        jdbcTemplate.update("UPDATE galgames SET view_count = view_count + 1 WHERE id = ?", id);
    }

    /** 是否已评分：防重表 galgame_ratings 存在 (galgame_id, user_id) 记录即已评过 */
    public boolean hasRated(long galgameId, long userId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM galgame_ratings WHERE galgame_id = ? AND user_id = ?",
                Integer.class, galgameId, userId);
        return count != null && count > 0;
    }

    /**
     * 新增评分（一人一票，0~10 分）：先向防重表 galgame_ratings 插记录，主键冲突（已评过）捕获
     * DuplicateKeyException 返回 1；成功插入后增量更新 galgames 的 rating_avg / rating_count
     * （新均值 = (旧均值×人数 + 新分) / (人数+1)，首次评分 avg 为 NULL 直接取新分），返回 0。
     */
    @Transactional
    public int rate(long galgameId, long userId, double score) {
        try {
            jdbcTemplate.update("INSERT INTO galgame_ratings (galgame_id, user_id) VALUES (?, ?)",
                    galgameId, userId);
        } catch (DuplicateKeyException e) {
            return 1;
        }
        jdbcTemplate.update(
                "UPDATE galgames SET rating_avg = COALESCE((rating_avg * rating_count + ?) / (rating_count + 1), ?), "
                        + "rating_count = rating_count + 1 WHERE id = ?",
                score, score, galgameId);
        return 0;
    }

    /** 删除 galgame；galgame_tags 由外键 ON DELETE CASCADE 一并清除 */
    public boolean deleteById(long id) {
        return jdbcTemplate.update("DELETE FROM galgames WHERE id = ?", id) > 0;
    }

    /** 批量写入 galgame 标签：过滤 null / 空字符串；tags 为空则跳过 */
    private void insertTags(Long galgameId, List<String> tags) {
        if (galgameId == null || tags == null || tags.isEmpty()) {
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
                "INSERT INTO galgame_tags (galgame_id, section_key) VALUES (?, ?)",
                cleaned, cleaned.size(), (ps, tag) -> {
                    ps.setLong(1, galgameId);
                    ps.setString(2, tag);
                });
    }

    private Galgame mapRow(ResultSet rs) throws SQLException {
        return new Galgame(
                rs.getLong("id"),
                rs.getString("name"),
                rs.getString("description"),
                rs.getString("image"),
                rs.getString("staff"),
                parseLinks(rs.getString("links")),
                parseTags(rs.getString("tags")),
                nullableLong(rs, "created_by"),
                rs.getTimestamp("created_at").toLocalDateTime(),
                rs.getTimestamp("updated_at").toLocalDateTime(),
                rs.getLong("view_count"),
                rs.getDate("release_date") == null ? null : rs.getDate("release_date").toLocalDate(),
                nullableDouble(rs, "rating_avg"),
                rs.getLong("rating_count"),
                rs.getString("status"),
                rs.getString("reject_reason"),
                nullableTimestamp(rs, "reviewed_at"),
                rs.getString("creator"));
    }

    /** 反序列化 links JSON 文本为 List<Link>；null / 空 / 解析失败一律回退空列表 */
    private List<Galgame.Link> parseLinks(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<Galgame.Link> links = objectMapper.readValue(json, new TypeReference<List<Galgame.Link>>() {
            });
            return links == null ? List.of() : links;
        } catch (tools.jackson.core.JacksonException e) {
            return List.of();
        }
    }

    /** GROUP_CONCAT 结果（逗号分隔的 section_key）拆成列表；null 为空列表 */
    private List<String> parseTags(String groupConcat) {
        if (groupConcat == null || groupConcat.isBlank()) {
            return List.of();
        }
        return Arrays.stream(groupConcat.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static Double nullableDouble(ResultSet rs, String column) throws SQLException {
        double value = rs.getDouble(column);
        return rs.wasNull() ? null : value;
    }

    private static LocalDateTime nullableTimestamp(ResultSet rs, String column) throws SQLException {
        java.sql.Timestamp ts = rs.getTimestamp(column);
        return ts == null ? null : ts.toLocalDateTime();
    }
}
