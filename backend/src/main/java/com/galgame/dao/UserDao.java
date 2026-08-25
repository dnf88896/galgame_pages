package com.galgame.dao;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import com.galgame.model.User;

/**
 * 用户数据访问层。
 */
@Repository
public class UserDao {

    private final JdbcTemplate jdbcTemplate;

    private static final RowMapper<User> USER_ROW_MAPPER = (ResultSet rs, int rowNum) ->
            new User(
                    rs.getLong("id"),
                    rs.getString("username"),
                    rs.getString("avatar_url"),
                    rs.getString("bio"),
                    rs.getTimestamp("created_at").toLocalDateTime());

    public UserDao(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<User> findById(Long id) {
        List<User> rows = jdbcTemplate.query(
                "SELECT id, username, avatar_url, bio, created_at FROM users WHERE id = ?",
                USER_ROW_MAPPER, id);
        return rows.stream().findFirst();
    }

    public Optional<User> findByUsername(String username) {
        List<User> rows = jdbcTemplate.query(
                "SELECT id, username, avatar_url, bio, created_at FROM users WHERE username = ?",
                USER_ROW_MAPPER, username);
        return rows.stream().findFirst();
    }

    /** 批量按 id 查用户；空集合返回空列表 */
    public List<User> findByIds(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        StringBuilder sql = new StringBuilder(
                "SELECT id, username, avatar_url, bio, created_at FROM users WHERE id IN (");
        for (int i = 0; i < ids.size(); i++) {
            if (i > 0) {
                sql.append(", ");
            }
            sql.append("?");
        }
        sql.append(")");
        return jdbcTemplate.query(sql.toString(), USER_ROW_MAPPER, ids.toArray());
    }

    public boolean existsByUsername(String username) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM users WHERE username = ?", Integer.class, username);
        return count != null && count > 0;
    }

    /** 取某用户名的密码哈希（登录校验用），用户不存在返回 empty */
    public Optional<String> findPasswordHashByUsername(String username) {
        List<String> rows = jdbcTemplate.query(
                "SELECT password_hash FROM users WHERE username = ?",
                (rs, rowNum) -> rs.getString("password_hash"), username);
        return rows.stream().findFirst();
    }

    /** 新增用户，返回数据库生成的自增 id */
    public Long insert(String username, String passwordHash) {
        String sql = "INSERT INTO users (username, password_hash) VALUES (?, ?)";
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, username);
            ps.setString(2, passwordHash);
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    public void updateBio(Long id, String bio) {
        jdbcTemplate.update("UPDATE users SET bio = ? WHERE id = ?", bio, id);
    }

    public void updateAvatarUrl(Long id, String avatarUrl) {
        jdbcTemplate.update("UPDATE users SET avatar_url = ? WHERE id = ?", avatarUrl, id);
    }
}
