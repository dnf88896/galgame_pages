package com.galgame.model;

import java.time.LocalDateTime;

/**
 * Galgame 标签实体，对应 tags 表。
 * <p>json 输出为 snake_case（由全局 Jackson 配置控制）：spoilerLevel → spoiler_level、galgameCount → galgame_count。
 * createdBy 在提交者被删除后为 null；status 为审核状态（approved已上架/pending待审核/rejected已拒绝，默认 approved）；
 * rejectReason 为拒绝理由（仅 rejected 时有值），reviewedAt 为审核时间，creator 为提交人昵称（昵称优先、账号名兜底）；
 * galgameCount 为该标签下已上架（status='approved'）作品实时计数（列表/详情展示用）。
 */
public record Tag(
        Long id,
        String name,
        String category,
        Integer spoilerLevel,
        String description,
        Integer galgameCount,
        Long createdBy,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        String status,
        String rejectReason,
        LocalDateTime reviewedAt,
        String creator) {
}
