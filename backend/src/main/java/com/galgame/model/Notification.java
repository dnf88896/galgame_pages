package com.galgame.model;

import java.time.LocalDateTime;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 消息通知实体，对应 notifications 表。
 * <p>actor_id / post_id / reply_id / title / content 均可空，序列化时省略。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Notification(
        Long id,
        Long userId,
        String type,
        Long actorId,
        Long postId,
        Long replyId,
        String title,
        String content,
        boolean isRead,
        LocalDateTime createdAt) {
}
