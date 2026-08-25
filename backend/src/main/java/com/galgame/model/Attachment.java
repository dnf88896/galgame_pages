package com.galgame.model;

import java.time.LocalDateTime;

/**
 * 附件实体，对应 attachments 表。
 * 对外字段 url 对应数据库的 url_path（DAO 映射时转换）。
 */
public record Attachment(
        Long id,
        Long postId,
        String originalName,
        String storedName,
        String mimeType,
        long size,
        String url,
        LocalDateTime createdAt) {
}
