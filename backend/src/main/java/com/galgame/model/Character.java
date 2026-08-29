package com.galgame.model;

import java.time.LocalDateTime;

/**
 * 角色实体，对应 characters 表。
 * <p>json 输出为 snake_case（由全局 Jackson 配置控制）；
 * createdBy 在作者被删除后为 null；status 为审核状态（approved已上架/pending待审核/rejected已拒绝，默认 approved），
 * rejectReason 为拒绝理由（仅 rejected 时有值），reviewedAt 为审核时间，creator 为提交人昵称（昵称优先、账号名兜底）。
 */
public record Character(
        Long id,
        String name,
        String description,
        String image,
        Integer viewCount,
        Long createdBy,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        String status,
        String rejectReason,
        LocalDateTime reviewedAt,
        String creator,
        String applyType,
        Long originalId,
        String originalName) {
}
