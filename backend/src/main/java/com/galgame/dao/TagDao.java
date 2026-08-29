package com.galgame.dao;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import com.galgame.model.GalgameTagBrief;
import com.galgame.model.Tag;

/**
 * Galgame 标签库数据访问层（仿 StaffDao / CompanyDao 全套套路）。
 * 审核状态 status（approved/pending/rejected）与萌点奖励防重 moe_awarded 复用 staffs 的模式。
 * <p>alias 为旧 gg-* section_key 别名（播种/迁移兼容用，前端不展示，查询不读）；
 * galgame_count 为实时计数：该标签下已上架（status='approved'）的 galgame 数。
 */
@Repository
public class TagDao {

    private static final String BASE_COLUMNS =
            "t.id, t.name, t.category, t.spoiler_level, t.description, "
            + "t.created_by, t.created_at, t.updated_at, t.status, t.reject_reason, t.reviewed_at, "
            + "(SELECT COALESCE(nickname, username) FROM users u WHERE u.id = t.created_by) AS creator, "
            + "(SELECT COUNT(*) FROM galgame_tag gt JOIN galgames g ON g.id = gt.galgame_id "
            + "WHERE gt.tag_id = t.id AND g.status = 'approved') AS galgame_count";

    private final JdbcTemplate jdbcTemplate;
    private final RowMapper<Tag> TAG_ROW_MAPPER;

    public TagDao(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.TAG_ROW_MAPPER = (rs, rowNum) -> mapRow(rs);
    }

    /** 新增标签，返回数据库生成的自增 id；alias 为旧 gg-* section_key 别名（用户创建为 null） */
    public Long insert(String name, String alias, String category, Integer spoilerLevel, String description,
                       Long createdBy, String status) {
        String sql = "INSERT INTO tags (name, alias, category, spoiler_level, description, created_by, status) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?)";
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, name);
            ps.setString(2, alias);
            ps.setString(3, category);
            ps.setInt(4, spoilerLevel == null ? 0 : spoilerLevel);
            ps.setString(5, description);
            ps.setObject(6, createdBy);
            ps.setString(7, status);
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    /** 更新标签（全量替换，不碰 status 列 → 编辑 pending 仍 pending、编辑 approved 仍 approved）；影响行数为 0 返回 false（调用方判 404） */
    public boolean update(long id, String name, String category, Integer spoilerLevel, String description) {
        return jdbcTemplate.update(
                "UPDATE tags SET name = ?, category = ?, spoiler_level = ?, description = ? WHERE id = ?",
                name, category, spoilerLevel == null ? 0 : spoilerLevel, description, id) > 0;
    }

    public Optional<Tag> findById(long id) {
        List<Tag> rows = jdbcTemplate.query(
                "SELECT " + BASE_COLUMNS + " FROM tags t WHERE t.id = ?",
                TAG_ROW_MAPPER, id);
        return rows.stream().findFirst();
    }

    public boolean existsById(long id) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM tags WHERE id = ?", Integer.class, id);
        return count != null && count > 0;
    }

    /** 名称是否已被占用（判重用，create/update 均调用；update 排除自身由 Controller 传当前 name 判断） */
    public boolean nameExists(String name) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM tags WHERE name = ?", Integer.class, name);
        return count != null && count > 0;
    }

    /**
     * 标签列表（公开，仅已上架）。固定过滤 status='approved'；q 按名称模糊匹配；category 按类别精确过滤；
     * sort：created（默认）按创建时间倒序 / name 按名称升序 / count 按该标签下已上架作品数倒序。
     */
    public List<Tag> findAll(String q, String category, String sort) {
        StringBuilder sql = new StringBuilder("SELECT " + BASE_COLUMNS + " FROM tags t WHERE 1=1 AND t.status = 'approved'");
        List<Object> args = new ArrayList<>();
        if (q != null && !q.isBlank()) {
            sql.append(" AND t.name LIKE CONCAT('%',?,'%')");
            args.add(q.trim());
        }
        if (category != null && !category.isBlank()) {
            sql.append(" AND t.category = ?");
            args.add(category.trim());
        }
        sql.append(orderBy(sort));
        return jdbcTemplate.query(sql.toString(), TAG_ROW_MAPPER, args.toArray());
    }

    private static String orderBy(String sort) {
        if ("name".equals(sort)) {
            return " ORDER BY t.name ASC, t.id ASC";
        }
        if ("count".equals(sort)) {
            return " ORDER BY galgame_count DESC, t.created_at DESC, t.id DESC";
        }
        return " ORDER BY t.created_at DESC, t.id DESC";
    }

    /** 远程搜索（公开，仅 approved）：供编辑选择器下拉，返回简要数组（不含 galgame_count）；q 为空时返回最新前 limit 条 */
    public List<GalgameTagBrief> search(String q, int limit) {
        String like = "%" + (q == null ? "" : q.trim()) + "%";
        return jdbcTemplate.query(
                "SELECT t.id, t.name, t.category, t.spoiler_level FROM tags t "
                        + "WHERE t.status = 'approved' AND t.name LIKE ? ORDER BY t.name, t.id LIMIT ?",
                (rs, rowNum) -> new GalgameTagBrief(
                        rs.getLong("id"),
                        rs.getString("name"),
                        rs.getString("category"),
                        rs.getInt("spoiler_level"),
                        null),
                like, limit);
    }

    /** 待审核列表（管理员审核页）：仅 status='pending'，按提交时间倒序，含提交人昵称 creator */
    public List<Tag> findPending() {
        return jdbcTemplate.query(
                "SELECT " + BASE_COLUMNS + " FROM tags t WHERE t.status = 'pending' ORDER BY t.created_at DESC",
                TAG_ROW_MAPPER);
    }

    /** 「我的提交」：某个用户创建的全部标签（含各审核状态），按创建时间倒序（同秒按 id 倒序兜底） */
    public List<Tag> findByCreator(long userId) {
        return jdbcTemplate.query(
                "SELECT " + BASE_COLUMNS + " FROM tags t WHERE t.created_by = ? ORDER BY t.created_at DESC, t.id DESC",
                TAG_ROW_MAPPER, userId);
    }

    /** 审核更新：设置 status 与拒绝理由，reviewed_at 刷新为当前时间 */
    public boolean updateStatus(long id, String status, String rejectReason) {
        return jdbcTemplate.update(
                "UPDATE tags SET status = ?, reject_reason = ?, reviewed_at = NOW() WHERE id = ?",
                status, rejectReason, id) > 0;
    }

    /** 审核通过萌点奖励防重：置位 moe_awarded=1，仅当原先为 0（未发放）时返回 true（原子，防重复发放） */
    public boolean claimMoeAward(long id) {
        return jdbcTemplate.update(
                "UPDATE tags SET moe_awarded = 1 WHERE id = ? AND moe_awarded = 0", id) > 0;
    }

    /** 删除标签；galgame_tag 关联由外键 ON DELETE CASCADE 一并清除 */
    public boolean deleteById(long id) {
        return jdbcTemplate.update("DELETE FROM tags WHERE id = ?", id) > 0;
    }

    private Tag mapRow(ResultSet rs) throws SQLException {
        return new Tag(
                rs.getLong("id"),
                rs.getString("name"),
                rs.getString("category"),
                rs.getInt("spoiler_level"),
                rs.getString("description"),
                rs.getInt("galgame_count"),
                nullableLong(rs, "created_by"),
                rs.getTimestamp("created_at").toLocalDateTime(),
                rs.getTimestamp("updated_at").toLocalDateTime(),
                rs.getString("status"),
                rs.getString("reject_reason"),
                nullableTimestamp(rs, "reviewed_at"),
                rs.getString("creator"));
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static LocalDateTime nullableTimestamp(ResultSet rs, String column) throws SQLException {
        java.sql.Timestamp ts = rs.getTimestamp(column);
        return ts == null ? null : ts.toLocalDateTime();
    }
}
