package com.galgame.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Optional;

import org.springframework.stereotype.Component;

import com.galgame.dao.AuthTokenDao;

/**
 * 不透明 token 的生成与校验。
 * <p>明文 token 为 32 字节随机 hex（64 字符），只在生成时返回客户端一次；
 * 入库时只存 SHA-256(token)。有效期 30 天。
 */
@Component
public class TokenService {

    private static final int TOKEN_BYTES = 32;
    private static final int TOKEN_LIFETIME_DAYS = 30;
    private static final String BEARER_PREFIX = "Bearer ";

    private final SecureRandom secureRandom = new SecureRandom();
    private final AuthTokenDao authTokenDao;

    public TokenService(AuthTokenDao authTokenDao) {
        this.authTokenDao = authTokenDao;
    }

    /** 生成新 token（存哈希），返回明文 token */
    public String createToken(long userId) {
        byte[] bytes = new byte[TOKEN_BYTES];
        secureRandom.nextBytes(bytes);
        String rawToken = HexFormat.of().formatHex(bytes);
        authTokenDao.insert(userId, sha256Hex(rawToken),
                LocalDateTime.now().plusDays(TOKEN_LIFETIME_DAYS));
        return rawToken;
    }

    /** 删除某明文 token 对应的记录（登出） */
    public void deleteToken(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            return;
        }
        authTokenDao.deleteByTokenHash(sha256Hex(rawToken.trim()));
    }

    /** 从 Authorization 请求头解析当前用户 id；无 token / 无效 / 过期则 empty */
    public Optional<Long> resolveUserId(String authorizationHeader) {
        if (authorizationHeader == null || authorizationHeader.isBlank()) {
            return Optional.empty();
        }
        String token = authorizationHeader.trim();
        if (token.startsWith(BEARER_PREFIX)) {
            token = token.substring(BEARER_PREFIX.length()).trim();
        }
        if (token.isEmpty()) {
            return Optional.empty();
        }
        return authTokenDao.findValidUserId(sha256Hex(token), LocalDateTime.now());
    }

    public static String sha256Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
