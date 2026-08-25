package com.galgame.dao;

import java.sql.ResultSet;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.galgame.model.User;

/**
 * 关注关系数据访问层。表 follows 主键 (follower_id, following_id)，
 * 每个用户对目标用户只保留一条记录，关注/取关走 toggle。
 */
@Repository
public class FollowDao {

    private final JdbcTemplate jdbcTemplate;

    private static final RowMapper<User> USER_ROW_MAPPER = (ResultSet rs, int rowNum) ->
            new User(
                    rs.getLong("id"),
                    rs.getString("username"),
                    rs.getString("avatar_url"),
                    rs.getString("bio"),
                    rs.getTimestamp("created_at").toLocalDateTime());

    public FollowDao(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 判断 followerId 是否已关注 targetId */
    public boolean isFollowing(long followerId, long targetId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM follows WHERE follower_id = ? AND following_id = ?",
                Integer.class, followerId, targetId);
        return count != null && count > 0;
    }

    /**
     * 关注 toggle。已关注则取消关注返回 false，未关注则关注返回 true。
     * 整个操作在一个事务里完成，避免并发下重复插入。
     */
    @Transactional
    public boolean toggle(long followerId, long targetId) {
        if (isFollowing(followerId, targetId)) {
            jdbcTemplate.update(
                    "DELETE FROM follows WHERE follower_id = ? AND following_id = ?", followerId, targetId);
            return false;
        }
        jdbcTemplate.update(
                "INSERT INTO follows (follower_id, following_id) VALUES (?, ?)", followerId, targetId);
        return true;
    }

    /** 该用户的粉丝数 */
    public int countFollowers(long userId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM follows WHERE following_id = ?", Integer.class, userId);
        return count == null ? 0 : count;
    }

    /** 该用户关注的人数 */
    public int countFollowing(long userId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM follows WHERE follower_id = ?", Integer.class, userId);
        return count == null ? 0 : count;
    }

    /** 关注该用户的粉丝列表（按关注时间倒序） */
    public List<User> findFollowers(long userId) {
        return jdbcTemplate.query(
                "SELECT u.id, u.username, u.avatar_url, u.bio, u.created_at "
                        + "FROM follows f JOIN users u ON u.id = f.follower_id "
                        + "WHERE f.following_id = ? ORDER BY f.created_at DESC",
                USER_ROW_MAPPER, userId);
    }

    /** 该用户关注的人的列表（按关注时间倒序） */
    public List<User> findFollowing(long userId) {
        return jdbcTemplate.query(
                "SELECT u.id, u.username, u.avatar_url, u.bio, u.created_at "
                        + "FROM follows f JOIN users u ON u.id = f.following_id "
                        + "WHERE f.follower_id = ? ORDER BY f.created_at DESC",
                USER_ROW_MAPPER, userId);
    }
}
