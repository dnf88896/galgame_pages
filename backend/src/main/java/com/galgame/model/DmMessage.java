package com.galgame.model;

import java.time.LocalDateTime;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 私聊消息实体，对应 dm_messages 表。
 * <p>isRead / isRecalled 为基本类型 boolean，照常输出 is_read / is_recalled。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DmMessage(
        Long id,
        Long senderId,
        String content,
        LocalDateTime createdAt,
        boolean isRead,
        boolean isRecalled) {
}
