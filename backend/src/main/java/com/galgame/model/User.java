package com.galgame.model;

import java.time.LocalDateTime;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 用户实体，对应 users 表。
 * <p>password_hash 永不对外序列化；avatar_url / bio 为 null 时省略。
 * admin_level 为管理员权限等级：0 普通用户，>0 为管理员（数字越大权限越高）。
 * hide_favorites 为是否隐藏收藏列表：0 公开（默认），1 隐藏。
 * ban_until 为封禁截止时间：null 未封禁；2099-12-31 23:59:59 为永久封禁；晚于当前时间才视为封禁中，过期自动解封。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record User(
        Long id,
        String username,
        String avatarUrl,
        String bio,
        LocalDateTime createdAt,
        Integer adminLevel,
        Integer hideFavorites,
        LocalDateTime banUntil) {
}
