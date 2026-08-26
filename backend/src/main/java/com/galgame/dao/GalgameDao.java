package com.galgame.dao;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

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
 */
@Repository
public class GalgameDao {

    private static final String BASE_COLUMNS =
            "g.id, g.name, g.description, g.image, g.staff, g.links, g.created_by, g.created_at, g.updated_at, "
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
    public Long insert(String name, String description, String image, String staff,
                       List<Galgame.Link> links, List<String> tags, long createdBy) {
        // Jackson 3 writeValueAsString 抛运行时异常，无需（也无法）捕获受检 IOException
        String linksJson = (links == null || links.isEmpty()) ? "[]" : objectMapper.writeValueAsString(links);
        String sql = "INSERT INTO galgames (name, description, image, staff, links, created_by) "
                + "VALUES (?, ?, ?, ?, ?, ?)";
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, name);
            ps.setString(2, description);
            ps.setString(3, image);
            ps.setString(4, staff);
            ps.setString(5, linksJson);
            ps.setLong(6, createdBy);
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
    public boolean update(long id, String name, String description, String image, String staff,
                          List<Galgame.Link> links, List<String> tags) {
        // Jackson 3 writeValueAsString 抛运行时异常，无需（也无法）捕获受检 IOException
        String linksJson = (links == null || links.isEmpty()) ? "[]" : objectMapper.writeValueAsString(links);
        int rows = jdbcTemplate.update(
                "UPDATE galgames SET name = ?, description = ?, image = ?, staff = ?, links = ? WHERE id = ?",
                name, description, image, staff, linksJson, id);
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
     * Galgame 列表。按条件动态拼接 WHERE：q 只按名称模糊匹配（作者/内容不搜）；
     * tags 为 gg-* 资源筛选标签，AND 语义：每标签一条 EXISTS 判断，
     * 一作须同时拥有所有指定标签；均无条件则返回全部。按创建时间倒序。
     */
    public List<Galgame> findAll(String q, List<String> tags) {
        StringBuilder sql = new StringBuilder("SELECT " + BASE_COLUMNS + " FROM galgames g WHERE 1=1");
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
        sql.append(" ORDER BY g.created_at DESC, g.id DESC");
        return jdbcTemplate.query(sql.toString(), GALGAME_ROW_MAPPER, args.toArray());
    }

    public boolean existsById(long id) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM galgames WHERE id = ?", Integer.class, id);
        return count != null && count > 0;
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
                rs.getTimestamp("updated_at").toLocalDateTime());
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
}
