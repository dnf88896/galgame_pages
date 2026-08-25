package com.galgame.dao;

import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * 屏蔽关系数据访问层。表 user_blocks 主键 (blocker_id, blocked_id)，
 * blocker 屏蔽 blocked，屏蔽/解除走 toggle。
 */
@Repository
public class BlockDao {

    private final JdbcTemplate jdbcTemplate;

    public BlockDao(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 判断 blockerId 是否已屏蔽 blockedId */
    public boolean isBlocked(long blockerId, long blockedId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM user_blocks WHERE blocker_id = ? AND blocked_id = ?",
                Integer.class, blockerId, blockedId);
        return count != null && count > 0;
    }

    /**
     * 屏蔽 toggle。已屏蔽则解除屏蔽返回 false，未屏蔽则屏蔽返回 true。
     * 整个操作在一个事务里完成，避免并发下重复插入。
     */
    @Transactional
    public boolean toggle(long blockerId, long blockedId) {
        if (isBlocked(blockerId, blockedId)) {
            jdbcTemplate.update(
                    "DELETE FROM user_blocks WHERE blocker_id = ? AND blocked_id = ?", blockerId, blockedId);
            return false;
        }
        jdbcTemplate.update(
                "INSERT INTO user_blocks (blocker_id, blocked_id) VALUES (?, ?)", blockerId, blockedId);
        return true;
    }

    /** 该用户屏蔽的所有用户 id（按屏蔽时间倒序） */
    public List<Long> findBlockedUserIds(long blockerId) {
        return jdbcTemplate.query(
                "SELECT blocked_id FROM user_blocks WHERE blocker_id = ? ORDER BY created_at DESC",
                (rs, rowNum) -> rs.getLong("blocked_id"), blockerId);
    }

    /** 屏蔽该用户的所有用户 id（按屏蔽时间倒序） */
    public List<Long> findBlockers(long userId) {
        return jdbcTemplate.query(
                "SELECT blocker_id FROM user_blocks WHERE blocked_id = ? ORDER BY created_at DESC",
                (rs, rowNum) -> rs.getLong("blocker_id"), userId);
    }
}
