package com.galgame.dao;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import com.galgame.model.EntityContributor;

/**
 * 条目贡献者数据访问层。
 * <p>entity_contributors 表以 (entry_type, entry_id, user_id) 复合主键记录「谁贡献了某条目」；
 * 创建者 / 修改申请提交者 / 管理员原地直改都被记为贡献者，同一人只记一条（INSERT IGNORE 天然防重）。
 */
@Repository
public class EntityContributorDao {

    private final JdbcTemplate jdbcTemplate;
    private final RowMapper<EntityContributor> CONTRIBUTOR_ROW_MAPPER;

    public EntityContributorDao(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.CONTRIBUTOR_ROW_MAPPER = (rs, rowNum) -> mapRow(rs);
    }

    /** 添加贡献者记录（INSERT IGNORE：复合主键天然防同一人重复贡献，重复添加静默忽略） */
    public void addContribution(String entryType, long entryId, long userId) {
        jdbcTemplate.update(
                "INSERT IGNORE INTO entity_contributors (entry_type, entry_id, user_id, created_at) VALUES (?, ?, ?, NOW())",
                entryType, entryId, userId);
    }

    /** 查询某条目的贡献者列表（含用户名/昵称/头像），按贡献时间正序（同秒按用户 id 兜底） */
    public List<EntityContributor> findContributors(String entryType, long entryId) {
        return jdbcTemplate.query(
                "SELECT u.id, u.username, u.nickname, u.avatar_url, ec.created_at "
                        + "FROM entity_contributors ec JOIN users u ON u.id = ec.user_id "
                        + "WHERE ec.entry_type = ? AND ec.entry_id = ? "
                        + "ORDER BY ec.created_at ASC, ec.user_id ASC",
                CONTRIBUTOR_ROW_MAPPER, entryType, entryId);
    }

    /** 删除某条目的全部贡献者记录（删除条目时清理） */
    public void deleteContributions(String entryType, long entryId) {
        jdbcTemplate.update("DELETE FROM entity_contributors WHERE entry_type = ? AND entry_id = ?",
                entryType, entryId);
    }

    private EntityContributor mapRow(ResultSet rs) throws SQLException {
        return new EntityContributor(
                rs.getLong("id"),
                rs.getString("username"),
                rs.getString("nickname"),
                rs.getString("avatar_url"),
                rs.getTimestamp("created_at").toLocalDateTime());
    }
}
