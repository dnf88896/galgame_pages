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
import org.springframework.transaction.annotation.Transactional;

import com.galgame.model.Company;

/**
 * 会社库数据访问层（仿 GalgameDao 精简版，无标签/评分/资源链接）。
 * 审核状态 status（approved/pending/rejected）与萌点奖励防重 moe_awarded 复用 galgames 的模式。
 * <p>「修改申请」影子行：对已上架（approved）会社，创建者提交修改时复制原记录为新行（apply_type='update'、original_id=原id、status='pending'），
 * 管理员审核通过 applyEdit 合并回原记录并删影子行；删除原记录前 deleteShadows 兜底清理影子行。
 */
@Repository
public class CompanyDao {

    private static final String BASE_COLUMNS =
            "c.id, c.name, c.description, c.website, c.logo_image, c.view_count, c.created_by, c.created_at, c.updated_at, "
            + "c.status, c.reject_reason, c.reviewed_at, "
            + "c.apply_type, c.original_id, "
            + "(SELECT c2.name FROM companies c2 WHERE c2.id = c.original_id) AS original_name, "
            + "(SELECT COALESCE(nickname, username) FROM users u WHERE u.id = c.created_by) AS creator";

    private final JdbcTemplate jdbcTemplate;
    private final RowMapper<Company> COMPANY_ROW_MAPPER;

    public CompanyDao(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.COMPANY_ROW_MAPPER = (rs, rowNum) -> mapRow(rs);
    }

    /** 新增会社，返回数据库生成的自增 id（applyType 普通创建传 "create"/originalId null，修改申请影子行传 "update"/原id） */
    public Long insert(String name, String description, String website, String logoImage, long createdBy, String status,
                       String applyType, Long originalId) {
        String sql = "INSERT INTO companies (name, description, website, logo_image, created_by, status, apply_type, original_id) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, name);
            ps.setString(2, description);
            ps.setString(3, website);
            ps.setString(4, logoImage);
            ps.setLong(5, createdBy);
            ps.setString(6, status);
            ps.setString(7, applyType);
            if (originalId != null) {
                ps.setLong(8, originalId);
            } else {
                ps.setNull(8, java.sql.Types.BIGINT);
            }
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    /** 创建「修改申请」影子行：复制原记录字段为新行（status='pending'、apply_type='update'、original_id=原id），返回影子行 id */
    public long createUpdateShadow(long originalId, String name, String description, String website, String logoImage, long userId) {
        String sql = "INSERT INTO companies (name, description, website, logo_image, created_by, created_at, updated_at, status, apply_type, original_id) "
                + "VALUES (?, ?, ?, ?, ?, NOW(), NOW(), 'pending', 'update', ?)";
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, name);
            ps.setString(2, description);
            ps.setString(3, website);
            ps.setString(4, logoImage);
            ps.setLong(5, userId);
            ps.setLong(6, originalId);
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    /**
     * 审核通过「修改申请」：把影子行字段合并回原记录（status 不动，updated_at 刷新），随后删除影子行。
     * @Transactional 保证「合并 + 删除」原子。
     */
    @Transactional
    public void applyEdit(long editId) {
        Company shadow = findById(editId)
                .orElseThrow(() -> new IllegalStateException("修改申请影子行不存在"));
        long originalId = shadow.originalId();
        jdbcTemplate.update(
                "UPDATE companies SET name = ?, description = ?, website = ?, logo_image = ?, updated_at = NOW() WHERE id = ?",
                shadow.name(), shadow.description(), shadow.website(), shadow.logoImage(), originalId);
        jdbcTemplate.update("DELETE FROM companies WHERE id = ?", editId);
    }

    /** 删除某原记录的全部影子行（删除原记录前兜底清理，返回影响行数） */
    public int deleteShadows(long originalId) {
        return jdbcTemplate.update("DELETE FROM companies WHERE original_id = ?", originalId);
    }

    /** 更新会社（全量替换）；影响行数为 0 返回 false（调用方判 404）；updated_at 由 ON UPDATE 自动刷新 */
    public boolean update(long id, String name, String description, String website, String logoImage) {
        return jdbcTemplate.update(
                "UPDATE companies SET name = ?, description = ?, website = ?, logo_image = ? WHERE id = ?",
                name, description, website, logoImage, id) > 0;
    }

    public Optional<Company> findById(long id) {
        List<Company> rows = jdbcTemplate.query(
                "SELECT " + BASE_COLUMNS + " FROM companies c WHERE c.id = ?",
                COMPANY_ROW_MAPPER, id);
        return rows.stream().findFirst();
    }

    public boolean existsById(long id) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM companies WHERE id = ?", Integer.class, id);
        return count != null && count > 0;
    }

    /** 待审核（status='pending'）的会社总数（含创建申请与修改申请影子行），全局审核待办计数用 */
    public int countPending() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM companies WHERE status = 'pending'", Integer.class);
        return count == null ? 0 : count;
    }

    /** 按 id 取已上架（approved）会社名称：galgame 关联会社时用于把 staff 快照覆盖为公司名；不存在 / 未上架返回 empty */
    public Optional<String> findApprovedNameById(long id) {
        List<String> rows = jdbcTemplate.query(
                "SELECT name FROM companies WHERE id = ? AND status = 'approved'",
                (rs, rowNum) -> rs.getString("name"), id);
        return rows.stream().findFirst();
    }

    /**
     * 会社列表（公开，仅已上架）。固定过滤 status='approved'；q 按名称模糊匹配；
     * sort：created（默认）按创建时间倒序 / views 总浏览数倒序 / views_asc 浏览数升序。
     */
    public List<Company> findAll(String q, String sort) {
        StringBuilder sql = new StringBuilder("SELECT " + BASE_COLUMNS + " FROM companies c WHERE 1=1 AND c.status = 'approved'");
        List<Object> args = new ArrayList<>();
        if (q != null && !q.isBlank()) {
            sql.append(" AND c.name LIKE CONCAT('%',?,'%')");
            args.add(q.trim());
        }
        sql.append(orderBy(sort));
        return jdbcTemplate.query(sql.toString(), COMPANY_ROW_MAPPER, args.toArray());
    }

    private static String orderBy(String sort) {
        if ("views".equals(sort)) {
            return " ORDER BY c.view_count DESC, c.created_at DESC, c.id DESC";
        }
        if ("views_asc".equals(sort)) {
            return " ORDER BY c.view_count ASC, c.created_at DESC, c.id DESC";
        }
        return " ORDER BY c.created_at DESC, c.id DESC";
    }

    /** 待审核列表（管理员审核页）：仅 status='pending'，按提交时间倒序，含提交人昵称 creator */
    public List<Company> findPending() {
        return jdbcTemplate.query(
                "SELECT " + BASE_COLUMNS + " FROM companies c WHERE c.status = 'pending' ORDER BY c.created_at DESC",
                COMPANY_ROW_MAPPER);
    }

    /** 「我的提交」：某个用户创建的全部会社（含各审核状态），按创建时间倒序（同秒按 id 倒序兜底） */
    public List<Company> findByCreator(long userId) {
        return jdbcTemplate.query(
                "SELECT " + BASE_COLUMNS + " FROM companies c WHERE c.created_by = ? ORDER BY c.created_at DESC, c.id DESC",
                COMPANY_ROW_MAPPER, userId);
    }

    /** 审核更新：设置 status 与拒绝理由，reviewed_at 刷新为当前时间 */
    public boolean updateStatus(long id, String status, String rejectReason) {
        return jdbcTemplate.update(
                "UPDATE companies SET status = ?, reject_reason = ?, reviewed_at = NOW() WHERE id = ?",
                status, rejectReason, id) > 0;
    }

    /** 审核通过萌点奖励防重：置位 moe_awarded=1，仅当原先为 0（未发放）时返回 true（原子，防重复发放） */
    public boolean claimMoeAward(long id) {
        return jdbcTemplate.update(
                "UPDATE companies SET moe_awarded = 1 WHERE id = ? AND moe_awarded = 0", id) > 0;
    }

    /** 浏览数 +1（详情页访问时调用） */
    public void incrementView(long id) {
        jdbcTemplate.update("UPDATE companies SET view_count = view_count + 1 WHERE id = ?", id);
    }

    /** 删除会社 */
    public boolean deleteById(long id) {
        return jdbcTemplate.update("DELETE FROM companies WHERE id = ?", id) > 0;
    }

    private Company mapRow(ResultSet rs) throws SQLException {
        return new Company(
                rs.getLong("id"),
                rs.getString("name"),
                rs.getString("description"),
                rs.getString("website"),
                rs.getString("logo_image"),
                rs.getLong("view_count"),
                nullableLong(rs, "created_by"),
                rs.getTimestamp("created_at").toLocalDateTime(),
                rs.getTimestamp("updated_at").toLocalDateTime(),
                rs.getString("status"),
                rs.getString("reject_reason"),
                nullableTimestamp(rs, "reviewed_at"),
                rs.getString("creator"),
                rs.getString("apply_type"),
                nullableLong(rs, "original_id"),
                rs.getString("original_name"));
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
