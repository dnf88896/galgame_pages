package com.galgame.dao;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 举报数据访问层。
 */
@Repository
public class ReportDao {

    private final JdbcTemplate jdbcTemplate;

    /**
     * 举报列表行。对 post 目标，replyContent/replyPostId 为 null；对 reply 目标，postTitle 为 null
     * （目标已被删除时 LEFT JOIN 匹配不到，对应列同样为 null）。
     */
    public record ReportRow(
            Long id,
            String targetType,
            Long targetId,
            String reason,
            LocalDateTime createdAt,
            Long reporterId,
            String reporterUsername,
            String postTitle,
            String replyContent,
            Long replyPostId,
            Integer status,
            LocalDateTime handledAt,
            String result) {
    }

    /** 举报查询共用列（findAll / findById 复用）；target_type 已用 r. 前缀消歧 */
    private static final String SELECT_COLUMNS =
            "SELECT r.id, r.target_type, r.target_id, r.reason, r.created_at,"
                    + " r.reporter_id, u.username AS reporter_username,"
                    + " p.title AS post_title,"
                    + " re.content AS reply_content, re.post_id AS reply_post_id,"
                    + " r.status, r.handled_at, r.result";

    private static final RowMapper<ReportRow> REPORT_ROW_MAPPER = (ResultSet rs, int rowNum) ->
            new ReportRow(
                    rs.getLong("id"),
                    rs.getString("target_type"),
                    rs.getLong("target_id"),
                    rs.getString("reason"),
                    rs.getTimestamp("created_at").toLocalDateTime(),
                    rs.getLong("reporter_id"),
                    rs.getString("reporter_username"),
                    rs.getString("post_title"),
                    rs.getString("reply_content"),
                    nullableLong(rs, "reply_post_id"),
                    rs.getInt("status"),
                    nullableTimestamp(rs, "handled_at"),
                    rs.getString("result"));

    public ReportDao(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 新增举报。UNIQUE (reporter_id, target_type, target_id) 冲突时 INSERT IGNORE 静默忽略，
     * 因此返回 true 表示本次新增，false 表示该举报人已举报过同一目标。
     */
    public boolean insertIfAbsent(Long reporterId, String targetType, Long targetId, String reason) {
        return jdbcTemplate.update(
                "INSERT IGNORE INTO reports (reporter_id, target_type, target_id, reason) VALUES (?, ?, ?, ?)",
                reporterId, targetType, targetId, reason) > 0;
    }

    /** 最近的举报列表（按 id 倒序，最多 200 条），附带举报人用户名与被举报目标标题/内容 */
    public List<ReportRow> findAll() {
        return jdbcTemplate.query(
                SELECT_COLUMNS
                        + " FROM reports r"
                        + " JOIN users u ON u.id = r.reporter_id"
                        + " LEFT JOIN posts p ON p.id = r.target_id AND r.target_type = 'post'"
                        + " LEFT JOIN replies re ON re.id = r.target_id AND r.target_type = 'reply'"
                        + " ORDER BY r.id DESC"
                        + " LIMIT 200",
                REPORT_ROW_MAPPER);
    }

    /** 按 id 查单条举报（同 findAll 的 JOIN + 全列，加 WHERE r.id = ?） */
    public Optional<ReportRow> findById(Long id) {
        List<ReportRow> rows = jdbcTemplate.query(
                SELECT_COLUMNS
                        + " FROM reports r"
                        + " JOIN users u ON u.id = r.reporter_id"
                        + " LEFT JOIN posts p ON p.id = r.target_id AND r.target_type = 'post'"
                        + " LEFT JOIN replies re ON re.id = r.target_id AND r.target_type = 'reply'"
                        + " WHERE r.id = ?",
                REPORT_ROW_MAPPER, id);
        return rows.stream().findFirst();
    }

    /** 标记举报已处理：status 1=已删除，2=已忽略；result 为处理结果（供通知举报人） */
    public void markHandled(Long id, int status, String result) {
        jdbcTemplate.update(
                "UPDATE reports SET status = ?, handled_at = NOW(), result = ? WHERE id = ?",
                status, result, id);
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static LocalDateTime nullableTimestamp(ResultSet rs, String column) throws SQLException {
        Timestamp ts = rs.getTimestamp(column);
        return ts == null ? null : ts.toLocalDateTime();
    }
}
