package com.galgame.model;

import java.time.LocalDateTime;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 用户实体，对应 users 表。
 * <p>password_hash 永不对外序列化；avatar_url / bio 为 null 时省略。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record User(
        Long id,
        String username,
        String avatarUrl,
        String bio,
        LocalDateTime createdAt) {
}
