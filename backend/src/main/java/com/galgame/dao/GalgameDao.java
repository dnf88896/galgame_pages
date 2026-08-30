package com.galgame.dao;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
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
import com.galgame.model.CharacterBrief;
import com.galgame.model.Galgame;
import com.galgame.model.GalgameCharacterLink;
import com.galgame.model.GalgameImage;
import com.galgame.model.GalgameStaffLink;
import com.galgame.model.GalgameTagBrief;
import com.galgame.model.NormalizedGalgameRequest;
import com.galgame.model.StaffBrief;

/**
 * Galgame 作品库数据访问层。使用 JdbcTemplate 访问 MySQL。
 * <p>links 以 JSON 数组文本（[{label,url}]）存 galgames.links，读写用注入的 JsonMapper（Jackson 3）序列化/反序列化；
 * tags 走 galgame_tag 多对多关联表，查询时用 JSON 对象数组子查询一次取回整行标签
 * （{id,name,category,spoiler_level}，仅 approved 标签），mapRow 用 ObjectMapper 解析成 List&lt;GalgameTagBrief&gt;；
 * categories 走旧表 galgame_tags（galgame_id, section_key），GROUP_CONCAT 子查询取回 24 个 gg-* section_key 逗号文本，
 * mapRow 拆分成 List&lt;String&gt;——分类与标签实体系统完全独立并存。
 * <p>评分：rating_avg / rating_count 存于 galgames，一人一票由防重表 galgame_ratings（主键 galgame_id+user_id）保证；
 * rate() 先插防重表，捕获 DuplicateKeyException（org.springframework.dao）判定已评过。
 */
@Repository
public class GalgameDao {

    private static final String BASE_COLUMNS =
            "g.id, g.name, g.description, g.image, g.staff, g.company_id, g.view_count, g.release_date, g.rating_avg, g.rating_count, g.links, g.created_by, g.created_at, g.updated_at, "
            + "g.status, g.reject_reason, g.reviewed_at, g.apply_type, g.original_id, "
            + "(SELECT g2.name FROM galgames g2 WHERE g2.id = g.original_id) AS original_name, "
            + "(SELECT COALESCE(nickname, username) FROM users u WHERE u.id = g.created_by) AS creator, "
            + "(SELECT CONCAT('[', GROUP_CONCAT(CONCAT('{\"id\":', t.id, ',\"name\":', JSON_QUOTE(t.name), "
            + "',\"category\":', JSON_QUOTE(t.category), ',\"spoiler_level\":', t.spoiler_level, '}') SEPARATOR ','), ']') "
            + "FROM galgame_tag gt2 JOIN tags t ON t.id = gt2.tag_id "
            + "WHERE gt2.galgame_id = g.id AND t.status = 'approved') AS tags, "
            + "(SELECT GROUP_CONCAT(gt3.section_key ORDER BY gt3.section_key) FROM galgame_tags gt3 WHERE gt3.galgame_id = g.id) AS categories";

    private final JdbcTemplate jdbcTemplate;
    private final JsonMapper objectMapper;
    private final RowMapper<Galgame> GALGAME_ROW_MAPPER;

    public GalgameDao(JdbcTemplate jdbcTemplate, JsonMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.GALGAME_ROW_MAPPER = (rs, rowNum) -> mapRow(rs);
    }

    /**
     * 新增 galgame（含 links JSON + tagIds + 制作人员/角色/相关系列关联），返回数据库生成的自增 id；
     * 主表与 galgame_tag / galgame_staff / galgame_character / galgame_related 在同一事务。
     */
    @Transactional
    public Long insert(String name, String description, String image, String staff, Long companyId, LocalDate releaseDate,
                       List<Galgame.Link> links, List<String> categories, List<Long> tagIds, List<GalgameStaffLink> staffLinks, List<GalgameCharacterLink> characterLinks,
                       List<Long> relatedIds, long createdBy, String status, String applyType, Long originalId) {
        // Jackson 3 writeValueAsString 抛运行时异常，无需（也无法）捕获受检 IOException
        String linksJson = (links == null || links.isEmpty()) ? "[]" : objectMapper.writeValueAsString(links);
        String sql = "INSERT INTO galgames (name, description, image, staff, company_id, release_date, links, created_by, status, apply_type, original_id) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, name);
            ps.setString(2, description);
            ps.setString(3, image);
            ps.setString(4, staff);
            ps.setObject(5, companyId);
            ps.setDate(6, releaseDate == null ? null : java.sql.Date.valueOf(releaseDate));
            ps.setString(7, linksJson);
            ps.setLong(8, createdBy);
            ps.setString(9, status);
            ps.setString(10, applyType);
            if (originalId != null) {
                ps.setLong(11, originalId);
            } else {
                ps.setNull(11, java.sql.Types.BIGINT);
            }
            return ps;
        }, keyHolder);
        Long galgameId = keyHolder.getKey().longValue();
        insertGalgameCategories(galgameId, categories);
        insertGalgameTags(galgameId, tagIds);
        insertGalgameStaffs(galgameId, staffLinks);
        insertGalgameCharacters(galgameId, characterLinks);
        insertGalgameRelated(galgameId, relatedIds);
        return galgameId;
    }

    /**
     * 更新 galgame（含 links JSON + tagIds + 制作人员/角色/相关系列关联 全量替换）。主表 UPDATE 影响行数为 0 返回 false（调用方判 404）；
     * tagIds / galgame_staff / galgame_character / galgame_related 先清空再按新值全量写入，与主表同事务；
     * updated_at 由数据库 ON UPDATE CURRENT_TIMESTAMP 自动刷新。
     */
    @Transactional
    public boolean update(long id, String name, String description, String image, String staff, Long companyId, LocalDate releaseDate,
                          List<Galgame.Link> links, List<String> categories, List<Long> tagIds, List<GalgameStaffLink> staffLinks, List<GalgameCharacterLink> characterLinks,
                          List<Long> relatedIds) {
        // Jackson 3 writeValueAsString 抛运行时异常，无需（也无法）捕获受检 IOException
        String linksJson = (links == null || links.isEmpty()) ? "[]" : objectMapper.writeValueAsString(links);
        int rows = jdbcTemplate.update(
                "UPDATE galgames SET name = ?, description = ?, image = ?, staff = ?, company_id = ?, release_date = ?, links = ? WHERE id = ?",
                name, description, image, staff, companyId,
                releaseDate == null ? null : java.sql.Date.valueOf(releaseDate), linksJson, id);
        if (rows == 0) {
            return false;
        }
        deleteGalgameCategories(id);
        insertGalgameCategories(id, categories);
        deleteGalgameTags(id);
        insertGalgameTags(id, tagIds);
        deleteGalgameStaffs(id);
        insertGalgameStaffs(id, staffLinks);
        deleteGalgameCharacters(id);
        insertGalgameCharacters(id, characterLinks);
        deleteGalgameRelated(id);
        insertGalgameRelated(id, relatedIds);
        return true;
    }

    /**
     * 创建「修改申请」影子行（普通用户对已上架 approved 作品提交修改时调用）：
     * 复制请求体内容为新行（status='pending'、apply_type='update'、original_id=原id、created_by=提交者），
     * 关联表（分类/标签/制作人员/角色/相关系列）写到影子行 id；画廊图不复制（留在原记录）。
     * 原记录保持 approved 上架显示不动。返回影子行 id（主表与关联表同一事务）。
     */
    @Transactional
    public long createUpdateShadow(long originalId, NormalizedGalgameRequest body, long userId) {
        String linksJson = (body.links() == null || body.links().isEmpty()) ? "[]" : objectMapper.writeValueAsString(body.links());
        String sql = "INSERT INTO galgames (name, description, image, staff, company_id, release_date, links, created_by, created_at, updated_at, status, apply_type, original_id) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, NOW(), NOW(), 'pending', 'update', ?)";
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, body.name());
            ps.setString(2, body.description());
            ps.setString(3, body.image());
            ps.setString(4, body.staff());
            ps.setObject(5, body.companyId());
            ps.setDate(6, body.releaseDate() == null ? null : java.sql.Date.valueOf(body.releaseDate()));
            ps.setString(7, linksJson);
            ps.setLong(8, userId);
            ps.setObject(9, originalId);
            return ps;
        }, keyHolder);
        Long shadowId = keyHolder.getKey().longValue();
        insertGalgameCategories(shadowId, body.categories());
        insertGalgameTags(shadowId, body.tagIds());
        insertGalgameStaffs(shadowId, body.staffLinks());
        insertGalgameCharacters(shadowId, body.characterLinks());
        insertGalgameRelated(shadowId, body.relatedIds());
        return shadowId;
    }

    /**
     * 审核通过「修改申请」：把影子行字段合并回原记录（status 不动保持 approved，updated_at 刷新），
     * 关联表迁移（先删原记录旧关联再移动影子关联，避免复合主键冲突），随后删除影子行。
     * @Transactional 保证「合并 + 迁移 + 删除」原子；无 original_id 直接抛错（调用方已先读行校验，这里防御）。
     */
    @Transactional
    public void applyEdit(long editId) {
        Galgame shadow = findById(editId)
                .orElseThrow(() -> new IllegalStateException("修改申请影子行不存在"));
        if (shadow.originalId() == null) {
            throw new IllegalStateException("修改申请缺少原记录 original_id");
        }
        long originalId = shadow.originalId();
        String linksJson = (shadow.links() == null || shadow.links().isEmpty()) ? "[]" : objectMapper.writeValueAsString(shadow.links());
        jdbcTemplate.update(
                "UPDATE galgames SET name = ?, description = ?, image = ?, staff = ?, company_id = ?, release_date = ?, links = ?, updated_at = NOW() WHERE id = ?",
                shadow.name(), shadow.description(), shadow.image(), shadow.staff(), shadow.companyId(),
                shadow.releaseDate() == null ? null : java.sql.Date.valueOf(shadow.releaseDate()),
                linksJson, originalId);
        // 关联表迁移：先删原记录旧关联，再把影子行关联改挂到原记录（避免复合主键冲突）
        jdbcTemplate.update("DELETE FROM galgame_tags WHERE galgame_id = ?", originalId);
        jdbcTemplate.update("UPDATE galgame_tags SET galgame_id = ? WHERE galgame_id = ?", originalId, editId);
        jdbcTemplate.update("DELETE FROM galgame_tag WHERE galgame_id = ?", originalId);
        jdbcTemplate.update("UPDATE galgame_tag SET galgame_id = ? WHERE galgame_id = ?", originalId, editId);
        jdbcTemplate.update("DELETE FROM galgame_staff WHERE galgame_id = ?", originalId);
        jdbcTemplate.update("UPDATE galgame_staff SET galgame_id = ? WHERE galgame_id = ?", originalId, editId);
        jdbcTemplate.update("DELETE FROM galgame_character WHERE galgame_id = ?", originalId);
        jdbcTemplate.update("UPDATE galgame_character SET galgame_id = ? WHERE galgame_id = ?", originalId, editId);
        jdbcTemplate.update("DELETE FROM galgame_related WHERE galgame_id = ?", originalId);
        jdbcTemplate.update("UPDATE galgame_related SET galgame_id = ? WHERE galgame_id = ?", originalId, editId);
        // 影子行不应有画廊图，删除（保险）
        jdbcTemplate.update("DELETE FROM galgame_images WHERE galgame_id = ?", editId);
        // 删除影子行（关联表已迁移到原记录，其余由 FK ON DELETE CASCADE 级联清理）
        jdbcTemplate.update("DELETE FROM galgames WHERE id = ?", editId);
    }

    /** 删除某原记录的全部「修改申请」影子行（删除原记录前兜底清理，返回影响行数）；影子行关联由 FK ON DELETE CASCADE 级联清除 */
    public int deleteShadows(long originalId) {
        return jdbcTemplate.update("DELETE FROM galgames WHERE original_id = ?", originalId);
    }

    /** 待审核（status='pending'）的 galgame 总数（含创建申请与修改申请影子行），全局审核待办计数用 */
    public int countPending() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM galgames WHERE status = 'pending'", Integer.class);
        return count == null ? 0 : count;
    }

    public Optional<Galgame> findById(long id) {
        List<Galgame> rows = jdbcTemplate.query(
                "SELECT " + BASE_COLUMNS + " FROM galgames g WHERE g.id = ?",
                GALGAME_ROW_MAPPER, id);
        return rows.stream().findFirst();
    }

    /** 兼容旧调用：不按分类/制作人员/角色筛选（委托给带 categories/staffId/characterId 的版本） */
    public List<Galgame> findAll(String q, List<Long> tags, String sort, String field, Long companyId) {
        return findAll(q, tags, null, sort, field, companyId, null, null, null, null);
    }

    /** 兼容旧调用：不按分类筛选（委托给带 categories 的版本） */
    public List<Galgame> findAll(String q, List<Long> tags, String sort, String field, Long companyId,
                                 Long staffId, Long characterId) {
        return findAll(q, tags, null, sort, field, companyId, staffId, characterId, null, null);
    }

    /**
     * Galgame 列表（公开，仅已上架）。固定过滤 status='approved'（pending/rejected 不进公开列表）；
     * 其余按条件动态拼接 WHERE：q 按 field 模糊匹配——field=staff 搜制作人员/会社（g.staff LIKE），
     * 默认/其它值搜名称（g.name LIKE）；
     * tags 为标签 id 数组，AND 语义：每标签一条 EXISTS 判断（关联表 galgame_tag 的 tag_id），
     * 一作须同时拥有所有指定标签；
     * categories 为旧分类 section_key 字符串数组（galgame_tags 表），AND 语义：每个分类一条 EXISTS 判断，
     * 一作须同时拥有所有指定分类；
     * companyId 按关联会社 id 筛选；
     * staffId / characterId 按关联的制作人员 / 角色筛选（EXISTS 关联表判断，可叠加，均仅 approved 条目）；
     * 均无条件则返回全部。
     * sort 取值：created（默认）按创建时间倒序（最新在上）；
     * views/views_asc 按浏览数倒序/升序；rating/rating_asc 按评分从高到低/从低到高；
     * release_date_desc/release_date_asc 发售日期从新到旧/从旧到新
     * （无发售日期 / 未评分的排最后）。
     */
    public List<Galgame> findAll(String q, List<Long> tags, List<String> categories, String sort, String field, Long companyId,
                                 Long staffId, Long characterId, Long limit, Long offset) {
        StringBuilder sql = new StringBuilder("SELECT " + BASE_COLUMNS + " FROM galgames g WHERE 1=1 AND g.status = 'approved'");
        List<Object> args = new ArrayList<>();
        if (q != null && !q.isBlank()) {
            // field 白名单：仅 staff 匹配制作人员/会社，其它一律匹配名称（不直接拼接用户输入，防列名注入）
            sql.append("staff".equalsIgnoreCase(field)
                    ? " AND g.staff LIKE CONCAT('%',?,'%')"
                    : " AND g.name LIKE CONCAT('%',?,'%')");
            args.add(q.trim());
        }
        if (tags != null) {
            for (Long tagId : tags) {
                if (tagId != null) {
                    sql.append(" AND EXISTS (SELECT 1 FROM galgame_tag gt WHERE gt.galgame_id = g.id AND gt.tag_id = ?)");
                    args.add(tagId);
                }
            }
        }
        if (categories != null) {
            for (String c : categories) {
                if (c != null && !c.isBlank()) {
                    sql.append(" AND EXISTS (SELECT 1 FROM galgame_tags gt WHERE gt.galgame_id = g.id AND gt.section_key = ?)");
                    args.add(c);
                }
            }
        }
        if (companyId != null) {
            sql.append(" AND g.company_id = ?");
            args.add(companyId);
        }
        if (staffId != null) {
            sql.append(" AND EXISTS (SELECT 1 FROM galgame_staff gs WHERE gs.galgame_id = g.id AND gs.staff_id = ?)");
            args.add(staffId);
        }
        if (characterId != null) {
            sql.append(" AND EXISTS (SELECT 1 FROM galgame_character gc WHERE gc.galgame_id = g.id AND gc.character_id = ?)");
            args.add(characterId);
        }
        sql.append(orderBy(sort));
        // 分页（可选）：limit>0 才启用，offset 默认 0。其它调用方不传 → 返回全部（与旧行为一致）
        if (limit != null && limit > 0) {
            sql.append(" LIMIT ?");
            args.add(limit);
            if (offset != null && offset > 0) {
                sql.append(" OFFSET ?");
                args.add(offset);
            }
        }
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

    /**
     * 查询某用户对某 galgame 的评分记录：
     * 未评过 → empty（rated=false）；已评过 → Optional.of(分数)。
     * 旧记录（迁移加列前）score 为 NULL，此时评过但分数未知，返回 0.0（保持「已评分」状态，详情回显 0 星，与历史行为一致）。
     */
    public Optional<Double> findScoreByUser(long galgameId, long userId) {
        List<Double> rows = jdbcTemplate.query(
                "SELECT score FROM galgame_ratings WHERE galgame_id = ? AND user_id = ?",
                (rs, i) -> {
                    double v = rs.getDouble("score");
                    return rs.wasNull() ? 0.0 : v;
                },
                galgameId, userId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    /**
     * 新增评分（一人一票，0~10 分）：先向防重表 galgame_ratings 插记录并写入该用户分数（详情页已评分回显），
     * 主键冲突（已评过）捕获 DuplicateKeyException 返回 1；成功插入后增量更新 galgames 的 rating_avg / rating_count
     * （新均值 = (旧均值×人数 + 新分) / (人数+1)，首次评分 avg 为 NULL 直接取新分），返回 0。
     */
    @Transactional
    public int rate(long galgameId, long userId, double score) {
        try {
            jdbcTemplate.update("INSERT INTO galgame_ratings (galgame_id, user_id, score) VALUES (?, ?, ?)",
                    galgameId, userId, score);
        } catch (DuplicateKeyException e) {
            return 1;
        }
        jdbcTemplate.update(
                "UPDATE galgames SET rating_avg = COALESCE((rating_avg * rating_count + ?) / (rating_count + 1), ?), "
                        + "rating_count = rating_count + 1 WHERE id = ?",
                score, score, galgameId);
        return 0;
    }

    /** 删除 galgame；galgame_tag / galgame_staff / galgame_character 由外键 ON DELETE CASCADE 一并清除 */
    public boolean deleteById(long id) {
        return jdbcTemplate.update("DELETE FROM galgames WHERE id = ?", id) > 0;
    }

    /** 校验标签是否存在且已上架（approved）：供创建/编辑 galgame 时校验关联标签（pending/rejected 不可选） */
    public boolean tagExists(long id) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM tags WHERE id = ? AND status = 'approved'", Integer.class, id);
        return count != null && count > 0;
    }

    /**
     * 某 galgame 关联的标签列表（仅 approved，按 category,id 排序），详情页展示；
     * 每项附全站该标签下已上架（status='approved'）作品计数 galgame_count。
     */
    public List<GalgameTagBrief> findTagsByGalgame(long galgameId) {
        return jdbcTemplate.query(
                "SELECT t.id, t.name, t.category, t.spoiler_level, "
                        + "(SELECT COUNT(*) FROM galgame_tag gt3 JOIN galgames g ON g.id = gt3.galgame_id "
                        + "WHERE gt3.tag_id = t.id AND g.status = 'approved') AS galgame_count "
                        + "FROM galgame_tag gt2 JOIN tags t ON t.id = gt2.tag_id "
                        + "WHERE gt2.galgame_id = ? AND t.status = 'approved' ORDER BY t.category, t.id",
                (rs, rowNum) -> new GalgameTagBrief(
                        rs.getLong("id"),
                        rs.getString("name"),
                        rs.getString("category"),
                        rs.getInt("spoiler_level"),
                        rs.getInt("galgame_count")),
                galgameId);
    }

    // ── 制作人员 / 角色关联（galgame_staff / galgame_character）─────────────────────────────

    /** 校验制作人员是否存在（供创建/编辑 galgame 时校验关联制作人员） */
    public boolean staffExists(long id) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM staffs WHERE id = ?", Integer.class, id);
        return count != null && count > 0;
    }

    /** 校验角色是否存在（供创建/编辑 galgame 时校验关联角色） */
    public boolean characterExists(long id) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM characters WHERE id = ?", Integer.class, id);
        return count != null && count > 0;
    }

    /** 某 galgame 关联的制作人员列表（仅已上架制作人员，按关联添加时间排序，后加入的排后面），详情页展示；description 为制作人员在作品中的职责/备注 */
    public List<StaffBrief> findStaffBriefs(long galgameId) {
        return jdbcTemplate.query(
                "SELECT s.id, s.name, gs.description FROM galgame_staff gs JOIN staffs s ON s.id = gs.staff_id "
                        + "WHERE gs.galgame_id = ? AND s.status = 'approved' ORDER BY gs.created_at, gs.staff_id",
                (rs, rowNum) -> new StaffBrief(rs.getLong("id"), rs.getString("name"), rs.getString("description")), galgameId);
    }

    /** 某 galgame 关联的角色列表（仅已上架角色，按关联添加时间排序，后加入的排后面），详情页展示；image 为角色封面图 URL；description 为角色在作品中的定位/备注 */
    public List<CharacterBrief> findCharacterBriefs(long galgameId) {
        return jdbcTemplate.query(
                "SELECT c.id, c.name, c.image, gc.description FROM galgame_character gc JOIN characters c ON c.id = gc.character_id "
                        + "WHERE gc.galgame_id = ? AND c.status = 'approved' ORDER BY gc.created_at, gc.character_id",
                (rs, rowNum) -> new CharacterBrief(rs.getLong("id"), rs.getString("name"), rs.getString("image"), rs.getString("description")), galgameId);
    }

    /** 批量写入 galgame-制作人员关联（含 description）：过滤 null / staffId 为 null；空则跳过 */
    public void insertGalgameStaffs(long galgameId, List<GalgameStaffLink> staffLinks) {
        if (galgameId == 0 || staffLinks == null || staffLinks.isEmpty()) {
            return;
        }
        List<GalgameStaffLink> cleaned = staffLinks.stream()
                .filter(link -> link != null && link.staffId() != null)
                .toList();
        if (cleaned.isEmpty()) {
            return;
        }
        jdbcTemplate.batchUpdate(
                "INSERT INTO galgame_staff (galgame_id, staff_id, description) VALUES (?, ?, ?)",
                cleaned, cleaned.size(), (ps, link) -> {
                    ps.setLong(1, galgameId);
                    ps.setLong(2, link.staffId());
                    ps.setString(3, link.description());
                });
    }

    /** 清空某 galgame 的全部制作人员关联（先删后插，全量替换） */
    public void deleteGalgameStaffs(long galgameId) {
        jdbcTemplate.update("DELETE FROM galgame_staff WHERE galgame_id = ?", galgameId);
    }

    /** 批量写入 galgame-角色关联（含 description）：过滤 null / characterId 为 null；空则跳过 */
    public void insertGalgameCharacters(long galgameId, List<GalgameCharacterLink> characterLinks) {
        if (galgameId == 0 || characterLinks == null || characterLinks.isEmpty()) {
            return;
        }
        List<GalgameCharacterLink> cleaned = characterLinks.stream()
                .filter(link -> link != null && link.characterId() != null)
                .toList();
        if (cleaned.isEmpty()) {
            return;
        }
        jdbcTemplate.batchUpdate(
                "INSERT INTO galgame_character (galgame_id, character_id, description) VALUES (?, ?, ?)",
                cleaned, cleaned.size(), (ps, link) -> {
                    ps.setLong(1, galgameId);
                    ps.setLong(2, link.characterId());
                    ps.setString(3, link.description());
                });
    }

    /** 清空某 galgame 的全部角色关联（先删后插，全量替换） */
    public void deleteGalgameCharacters(long galgameId) {
        jdbcTemplate.update("DELETE FROM galgame_character WHERE galgame_id = ?", galgameId);
    }

    /** 批量写入 galgame-分类关联（旧表 galgame_tags）：过滤 null/blank；categories 为空则跳过 */
    public void insertGalgameCategories(Long galgameId, List<String> categories) {
        if (galgameId == null || categories == null || categories.isEmpty()) {
            return;
        }
        List<String> cleaned = categories.stream()
                .filter(c -> c != null && !c.isBlank())
                .toList();
        if (cleaned.isEmpty()) {
            return;
        }
        jdbcTemplate.batchUpdate(
                "INSERT INTO galgame_tags (galgame_id, section_key) VALUES (?, ?)",
                cleaned, cleaned.size(), (ps, sectionKey) -> {
                    ps.setLong(1, galgameId);
                    ps.setString(2, sectionKey);
                });
    }

    /** 清空某 galgame 的全部分类关联（先删后插，全量替换） */
    public void deleteGalgameCategories(long galgameId) {
        jdbcTemplate.update("DELETE FROM galgame_tags WHERE galgame_id = ?", galgameId);
    }

    /** 批量写入 galgame-标签关联：过滤 null；tagIds 为空则跳过 */
    public void insertGalgameTags(Long galgameId, List<Long> tagIds) {
        if (galgameId == null || tagIds == null || tagIds.isEmpty()) {
            return;
        }
        List<Long> cleaned = tagIds.stream().filter(id -> id != null).toList();
        if (cleaned.isEmpty()) {
            return;
        }
        jdbcTemplate.batchUpdate(
                "INSERT INTO galgame_tag (galgame_id, tag_id) VALUES (?, ?)",
                cleaned, cleaned.size(), (ps, tagId) -> {
                    ps.setLong(1, galgameId);
                    ps.setLong(2, tagId);
                });
    }

    /** 清空某 galgame 的全部标签关联（先删后插，全量替换） */
    public void deleteGalgameTags(long galgameId) {
        jdbcTemplate.update("DELETE FROM galgame_tag WHERE galgame_id = ?", galgameId);
    }

    // ── 画廊多图（galgame_images）─────────────────────────────

    /** 某 galgame 的画廊图片列表（按 sort_order, id 排序 = 上传顺序），详情页展示 */
    public List<GalgameImage> findImages(long galgameId) {
        return jdbcTemplate.query(
                "SELECT id, url FROM galgame_images WHERE galgame_id = ? ORDER BY sort_order, id",
                (rs, rowNum) -> new GalgameImage(rs.getLong("id"), rs.getString("url")),
                galgameId);
    }

    /** 批量写入画廊图片：按列表顺序赋 sort_order（0 起递增）；过滤 null/blank；空则跳过 */
    public void insertImages(long galgameId, List<String> urls) {
        if (galgameId == 0 || urls == null || urls.isEmpty()) {
            return;
        }
        List<String> cleaned = urls.stream().filter(u -> u != null && !u.isBlank()).toList();
        if (cleaned.isEmpty()) {
            return;
        }
        int[] sortOrder = {0};
        jdbcTemplate.batchUpdate(
                "INSERT INTO galgame_images (galgame_id, url, sort_order) VALUES (?, ?, ?)",
                cleaned, cleaned.size(), (ps, url) -> {
                    ps.setLong(1, galgameId);
                    ps.setString(2, url);
                    ps.setInt(3, sortOrder[0]++);
                });
    }

    /** 删除单张画廊图片，返回影响行数（0 = 不存在） */
    public int deleteImage(long imageId) {
        return jdbcTemplate.update("DELETE FROM galgame_images WHERE id = ?", imageId);
    }

    /** 查询某画廊图片所属 galgame id（删除图片前先查归属做权限判断）；图片不存在返回 empty */
    public Optional<Long> findImageGalgameId(long imageId) {
        List<Long> rows = jdbcTemplate.query(
                "SELECT galgame_id FROM galgame_images WHERE id = ?",
                (rs, rowNum) -> rs.getLong("galgame_id"), imageId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    // ── 相关系列（galgame_related）─────────────────────────────

    /** 校验关联目标是否存在且已上架（approved）：pending/rejected 不可被关联 */
    public boolean relatedExists(long id) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM galgames WHERE id = ? AND status = 'approved'", Integer.class, id);
        return count != null && count > 0;
    }

    /** 批量写入 galgame-相关系列关联：过滤 null；relatedIds 为空则跳过 */
    public void insertGalgameRelated(long galgameId, List<Long> relatedIds) {
        if (galgameId == 0 || relatedIds == null || relatedIds.isEmpty()) {
            return;
        }
        List<Long> cleaned = relatedIds.stream().filter(id -> id != null).toList();
        if (cleaned.isEmpty()) {
            return;
        }
        jdbcTemplate.batchUpdate(
                "INSERT INTO galgame_related (galgame_id, related_id) VALUES (?, ?)",
                cleaned, cleaned.size(), (ps, relatedId) -> {
                    ps.setLong(1, galgameId);
                    ps.setLong(2, relatedId);
                });
    }

    /** 清空某 galgame 的全部相关系列关联（先删后插，全量替换） */
    public void deleteGalgameRelated(long galgameId) {
        jdbcTemplate.update("DELETE FROM galgame_related WHERE galgame_id = ?", galgameId);
    }

    /**
     * 某 galgame 的相关系列列表（仅已上架 approved 作品，按关联时间稳定排序），详情页展示；
     * 复用 BASE_COLUMNS 的 g. 别名（JOIN 场景下 g 指向 galgames），返回完整 Galgame 实体。
     */
    public List<Galgame> findRelatedGames(long galgameId) {
        return jdbcTemplate.query(
                "SELECT " + BASE_COLUMNS + " FROM galgame_related gr JOIN galgames g ON g.id = gr.related_id "
                        + "WHERE gr.galgame_id = ? AND g.status = 'approved' ORDER BY gr.created_at, g.id",
                GALGAME_ROW_MAPPER, galgameId);
    }

    private Galgame mapRow(ResultSet rs) throws SQLException {
        return new Galgame(
                rs.getLong("id"),
                rs.getString("name"),
                rs.getString("description"),
                rs.getString("image"),
                rs.getString("staff"),
                nullableLong(rs, "company_id"),
                parseLinks(rs.getString("links")),
                parseCategories(rs.getString("categories")),
                parseTagsJson(rs.getString("tags")),
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
                rs.getString("creator"),
                rs.getString("apply_type"),
                nullableLong(rs, "original_id"),
                rs.getString("original_name"));
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

    /** 解析分类 section_key 逗号拼接文本（BASE_COLUMNS 的 GROUP_CONCAT）为 List&lt;String&gt;；null / 空 → 空列表，空串过滤 */
    private List<String> parseCategories(String s) {
        if (s == null || s.isBlank()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (String c : s.split(",")) {
            if (c != null && !c.isBlank()) {
                result.add(c);
            }
        }
        return result;
    }

    /** 反序列化 tags JSON 文本（对象数组 [{id,name,category,spoiler_level}]）为 List&lt;GalgameTagBrief&gt;；null / 空 / 解析失败一律回退空列表 */
    private List<GalgameTagBrief> parseTagsJson(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<GalgameTagBrief> tags = objectMapper.readValue(json, new TypeReference<List<GalgameTagBrief>>() {
            });
            return tags == null ? List.of() : tags;
        } catch (tools.jackson.core.JacksonException e) {
            return List.of();
        }
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
