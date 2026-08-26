package com.galgame.model;

import java.time.LocalDateTime;

/**
 * 举报记录，对应 reports 表。
 * targetType 区分 post（帖子）/ reply（评论）；同一举报人对同一目标仅一条记录（UNIQUE 约束）。
 */
public record Report(
        Long id,
        String targetType,
        Long targetId,
        String reason,
        LocalDateTime createdAt,
        Long reporterId) {
}
