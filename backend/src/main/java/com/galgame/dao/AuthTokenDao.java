package com.galgame.dao;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 登录 token 数据访问层。
 * <p>表中只存 token 的 SHA-256 哈希（token_hash），明文 token 只返回给客户端一次。
 */
@Repository
public class AuthTokenDao {

    private final JdbcTemplate jdbcTemplate;

    public AuthTokenDao(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void insert(Long userId, String tokenHash, LocalDateTime expiresAt) {
        jdbcTemplate.update(
                "INSERT INTO auth_tokens (user_id, token_hash, expires_at) VALUES (?, ?, ?)",
                userId, tokenHash, Timestamp.valueOf(expiresAt));
    }

    /** 查询未过期的有效 token 对应的用户 id */
    public Optional<Long> findValidUserId(String tokenHash, LocalDateTime now) {
        List<Long> rows = jdbcTemplate.query(
                "SELECT user_id FROM auth_tokens WHERE token_hash = ? AND expires_at > ?",
                (rs, rowNum) -> rs.getLong("user_id"), tokenHash, Timestamp.valueOf(now));
        return rows.stream().findFirst();
    }

    public void deleteByTokenHash(String tokenHash) {
        jdbcTemplate.update("DELETE FROM auth_tokens WHERE token_hash = ?", tokenHash);
    }
}
