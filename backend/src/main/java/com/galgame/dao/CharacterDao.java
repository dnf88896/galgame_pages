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

import com.galgame.model.Character;
import com.galgame.model.StaffBrief;

/**
 * 角色库数据访问层（仿 CompanyDao / StaffDao，对称的 staff_character 多对多关联）。
 * 审核状态 status（approved/pending/rejected）与萌点奖励防重 moe_awarded 复用 companies 的模式。
 * <p>角色与制作人员通过 staff_character 多对多关联：创建/编辑时先删后插（全量替换），
 * insertWithStaffs / updateWithStaffs 与主表写入同事务。
 */
@Repository
public class CharacterDao {

    private static final String BASE_COLUMNS =
            "ch.id, ch.name, ch.description, ch.image, ch.view_count, ch.created_by, ch.created_at, ch.updated_at, "
            + "ch.status, ch.reject_reason, ch.reviewed_at, "
            + "ch.apply_type, ch.original_id, "
            + "(SELECT ch2.name FROM characters ch2 WHERE ch2.id = ch.original_id) AS original_name, "
            + "(SELECT COALESCE(nickname, username) FROM users u WHERE u.id = ch.created_by) AS creator";

    private final JdbcTemplate jdbcTemplate;
    private final RowMapper<Character> CHARACTER_ROW_MAPPER;

    public CharacterDao(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.CHARACTER_ROW_MAPPER = (rs, rowNum) -> mapRow(rs);
    }

    /** 新增角色 + 制作人员关联（同一事务），返回数据库生成的自增 id（applyType 普通创建传 "create"/originalId null，修改申请影子行传 "update"/原id） */
    @Transactional
    public Long insertWithStaffs(String name, String description, String image, long createdBy, String status, List<Long> staffIds,
                                 String applyType, Long originalId) {
        String sql = "INSERT INTO characters (name, description, image, created_by, status, apply_type, original_id) VALUES (?, ?, ?, ?, ?, ?, ?)";
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, name);
            ps.setString(2, description);
            ps.setString(3, image);
            ps.setLong(4, createdBy);
            ps.setString(5, status);
            ps.setString(6, applyType);
            if (originalId != null) {
                ps.setLong(7, originalId);
            } else {
                ps.setNull(7, java.sql.Types.BIGINT);
            }
            return ps;
        }, keyHolder);
        Long characterId = keyHolder.getKey().longValue();
        insertStaffLinks(characterId, staffIds);
        return characterId;
    }

    /** 创建「修改申请」影子行：复制原记录字段为新行（status='pending'、apply_type='update'、original_id=原id），制作人员关联写到影子行 id，返回影子行 id */
    @Transactional
    public long createUpdateShadow(long originalId, String name, String description, String image, long userId, List<Long> staffIds) {
        String sql = "INSERT INTO characters (name, description, image, created_by, created_at, updated_at, status, apply_type, original_id) "
                + "VALUES (?, ?, ?, ?, NOW(), NOW(), 'pending', 'update', ?)";
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, name);
            ps.setString(2, description);
            ps.setString(3, image);
            ps.setLong(4, userId);
            ps.setLong(5, originalId);
            return ps;
        }, keyHolder);
        long characterId = keyHolder.getKey().longValue();
        insertStaffLinks(characterId, staffIds);
        return characterId;
    }

    /**
     * 审核通过「修改申请」：把影子行字段合并回原记录（status 不动，updated_at 刷新），
     * 制作人员关联迁移（先清原记录旧关联，再把影子行关联改挂到原记录 id），随后删除影子行。
     * @Transactional 保证「合并 + 迁移关联 + 删除」原子。
     */
    @Transactional
    public void applyEdit(long editId) {
        Character shadow = findById(editId)
                .orElseThrow(() -> new IllegalStateException("修改申请影子行不存在"));
        long originalId = shadow.originalId();
        jdbcTemplate.update(
                "UPDATE characters SET name = ?, description = ?, image = ?, updated_at = NOW() WHERE id = ?",
                shadow.name(), shadow.description(), shadow.image(), originalId);
        // 关联迁移：先清原记录旧关联（避免主键冲突），再把影子行关联改挂到原记录
        jdbcTemplate.update("DELETE FROM staff_character WHERE character_id = ?", originalId);
        jdbcTemplate.update("UPDATE staff_character SET character_id = ? WHERE character_id = ?", originalId, editId);
        jdbcTemplate.update("DELETE FROM characters WHERE id = ?", editId);
    }

    /** 删除某原记录的全部影子行（删除原记录前兜底清理，返回影响行数） */
    public int deleteShadows(long originalId) {
        return jdbcTemplate.update("DELETE FROM characters WHERE original_id = ?", originalId);
    }

    /** 更新角色 + 制作人员关联全量替换（同一事务）；影响行数为 0 返回 false（调用方判 404）；updated_at 由 ON UPDATE 自动刷新 */
    @Transactional
    public boolean updateWithStaffs(long id, String name, String description, String image, List<Long> staffIds) {
        int rows = jdbcTemplate.update(
                "UPDATE characters SET name = ?, description = ?, image = ? WHERE id = ?",
                name, description, image, id);
        if (rows == 0) {
            return false;
        }
        deleteStaffLinks(id);
        insertStaffLinks(id, staffIds);
        return true;
    }

    public Optional<Character> findById(long id) {
        List<Character> rows = jdbcTemplate.query(
                "SELECT " + BASE_COLUMNS + " FROM characters ch WHERE ch.id = ?",
                CHARACTER_ROW_MAPPER, id);
        return rows.stream().findFirst();
    }

    public boolean existsById(long id) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM characters WHERE id = ?", Integer.class, id);
        return count != null && count > 0;
    }

    /** 待审核（status='pending'）的角色总数（含创建申请与修改申请影子行），全局审核待办计数用 */
    public int countPending() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM characters WHERE status = 'pending'", Integer.class);
        return count == null ? 0 : count;
    }

    /**
     * 角色列表（公开，仅已上架）。固定过滤 status='approved'；q 按名称模糊匹配；
     * sort：created（默认）按创建时间倒序 / views 总浏览数倒序。
     */
    public List<Character> findAll(String q, String sort) {
        StringBuilder sql = new StringBuilder("SELECT " + BASE_COLUMNS + " FROM characters ch WHERE 1=1 AND ch.status = 'approved'");
        List<Object> args = new ArrayList<>();
        if (q != null && !q.isBlank()) {
            sql.append(" AND ch.name LIKE CONCAT('%',?,'%')");
            args.add(q.trim());
        }
        sql.append(orderBy(sort));
        return jdbcTemplate.query(sql.toString(), CHARACTER_ROW_MAPPER, args.toArray());
    }

    private static String orderBy(String sort) {
        if ("views".equals(sort)) {
            return " ORDER BY ch.view_count DESC, ch.created_at DESC, ch.id DESC";
        }
        return " ORDER BY ch.created_at DESC, ch.id DESC";
    }

    /** 待审核列表（管理员审核页）：仅 status='pending'，按提交时间倒序，含提交人昵称 creator */
    public List<Character> findPending() {
        return jdbcTemplate.query(
                "SELECT " + BASE_COLUMNS + " FROM characters ch WHERE ch.status = 'pending' ORDER BY ch.created_at DESC",
                CHARACTER_ROW_MAPPER);
    }

    /** 「我的提交」：某个用户创建的全部角色（含各审核状态），按创建时间倒序（同秒按 id 倒序兜底） */
    public List<Character> findByCreator(long userId) {
        return jdbcTemplate.query(
                "SELECT " + BASE_COLUMNS + " FROM characters ch WHERE ch.created_by = ? ORDER BY ch.created_at DESC, ch.id DESC",
                CHARACTER_ROW_MAPPER, userId);
    }

    /** 审核更新：设置 status 与拒绝理由，reviewed_at 刷新为当前时间 */
    public boolean updateStatus(long id, String status, String rejectReason) {
        return jdbcTemplate.update(
                "UPDATE characters SET status = ?, reject_reason = ?, reviewed_at = NOW() WHERE id = ?",
                status, rejectReason, id) > 0;
    }

    /** 审核通过萌点奖励防重：置位 moe_awarded=1，仅当原先为 0（未发放）时返回 true（原子，防重复发放） */
    public boolean claimMoeAward(long id) {
        return jdbcTemplate.update(
                "UPDATE characters SET moe_awarded = 1 WHERE id = ? AND moe_awarded = 0", id) > 0;
    }

    /** 浏览数 +1（详情页访问时调用） */
    public void incrementView(long id) {
        jdbcTemplate.update("UPDATE characters SET view_count = view_count + 1 WHERE id = ?", id);
    }

    /** 删除角色；staff_character 关联由外键 ON DELETE CASCADE 一并清除 */
    public boolean deleteById(long id) {
        return jdbcTemplate.update("DELETE FROM characters WHERE id = ?", id) > 0;
    }

    // ── 关联（staff_character）─────────────────────────────

    /** 校验制作人员是否存在（供创建/编辑角色时校验关联制作人员） */
    public boolean staffExists(long id) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM staffs WHERE id = ?", Integer.class, id);
        return count != null && count > 0;
    }

    /** 某角色关联的制作人员列表（仅已上架制作人员，按名称排序） */
    public List<StaffBrief> findLinkedStaffs(long characterId) {
        return jdbcTemplate.query(
                "SELECT s.id, s.name FROM staff_character sc JOIN staffs s ON s.id = sc.staff_id "
                        + "WHERE sc.character_id = ? AND s.status = 'approved' ORDER BY s.name, s.id",
                (rs, rowNum) -> new StaffBrief(rs.getLong("id"), rs.getString("name"), null), characterId);
    }

    /** 批量写入角色-制作人员关联：过滤 null；空则跳过 */
    public void insertStaffLinks(long characterId, List<Long> staffIds) {
        if (characterId == 0 || staffIds == null || staffIds.isEmpty()) {
            return;
        }
        List<Long> cleaned = staffIds.stream().filter(sid -> sid != null).toList();
        if (cleaned.isEmpty()) {
            return;
        }
        jdbcTemplate.batchUpdate(
                "INSERT INTO staff_character (staff_id, character_id) VALUES (?, ?)",
                cleaned, cleaned.size(), (ps, staffId) -> {
                    ps.setLong(1, staffId);
                    ps.setLong(2, characterId);
                });
    }

    /** 清空某角色的全部制作人员关联（先删后插，全量替换） */
    public void deleteStaffLinks(long characterId) {
        jdbcTemplate.update("DELETE FROM staff_character WHERE character_id = ?", characterId);
    }

    private Character mapRow(ResultSet rs) throws SQLException {
        return new Character(
                rs.getLong("id"),
                rs.getString("name"),
                rs.getString("description"),
                rs.getString("image"),
                rs.getInt("view_count"),
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
