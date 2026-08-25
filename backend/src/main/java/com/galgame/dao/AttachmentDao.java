package com.galgame.dao;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import com.galgame.model.Attachment;

/**
 * 附件数据访问层。
 */
@Repository
public class AttachmentDao {

    private final JdbcTemplate jdbcTemplate;

    /** 数据库 url_path 列对外映射为 url 字段 */
    private static final RowMapper<Attachment> ATTACHMENT_ROW_MAPPER = (ResultSet rs, int rowNum) ->
            new Attachment(
                    rs.getLong("id"),
                    rs.getLong("post_id"),
                    rs.getString("original_name"),
                    rs.getString("stored_name"),
                    rs.getString("mime_type"),
                    rs.getLong("size"),
                    rs.getString("url_path"),
                    rs.getTimestamp("created_at").toLocalDateTime());

    public AttachmentDao(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<Attachment> findByPostId(Long postId) {
        String sql = "SELECT id, post_id, original_name, stored_name, mime_type, size, url_path, created_at "
                + "FROM attachments WHERE post_id = ? ORDER BY id ASC";
        return jdbcTemplate.query(sql, ATTACHMENT_ROW_MAPPER, postId);
    }

    /** 批量查询多个帖子的附件，返回 postId -> List<Attachment>（防 N+1） */
    public Map<Long, List<Attachment>> findByPostIds(List<Long> postIds) {
        if (postIds == null || postIds.isEmpty()) {
            return Map.of();
        }
        String inClause = postIds.stream().map(String::valueOf).collect(Collectors.joining(","));
        String sql = "SELECT id, post_id, original_name, stored_name, mime_type, size, url_path, created_at "
                + "FROM attachments WHERE post_id IN (" + inClause + ") ORDER BY id ASC";
        List<Attachment> rows = jdbcTemplate.query(sql, ATTACHMENT_ROW_MAPPER);
        Map<Long, List<Attachment>> byPost = new LinkedHashMap<>();
        for (Attachment att : rows) {
            byPost.computeIfAbsent(att.postId(), k -> new ArrayList<>()).add(att);
        }
        return byPost;
    }

    public void insert(Long postId, String originalName, String storedName,
                       String mimeType, long size, String urlPath) {
        String sql = "INSERT INTO attachments (post_id, original_name, stored_name, mime_type, size, url_path) "
                + "VALUES (?, ?, ?, ?, ?, ?)";
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, postId);
            ps.setString(2, originalName);
            ps.setString(3, storedName);
            ps.setString(4, mimeType);
            ps.setLong(5, size);
            ps.setString(6, urlPath);
            return ps;
        });
    }
}
