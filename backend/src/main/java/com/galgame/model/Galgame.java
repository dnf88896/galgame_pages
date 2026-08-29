package com.galgame.model;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Galgame 作品实体，对应 galgames 表。
 * <p>json 输出为 snake_case（由全局 Jackson 配置控制）；
 * links / tags 恒非 null（无数据时为空数组）；createdBy 在作者被删除后为 null；
 * ratingAvg 为评分平均分（null=暂无评分），ratingCount 为评分人数（一人一票）；
 * status 为审核状态（approved已上架/pending待审核/rejected已拒绝，默认 approved），
 * rejectReason 为拒绝理由（仅 rejected 时有值），reviewedAt 为审核时间，creator 为提交人昵称（昵称优先、账号名兜底）；
 * applyType 为申请类型（create创建申请 / update修改申请[影子行]，默认 create），originalId 为修改申请影子行指向的原记录 id（apply_type=update 时有值），
 * originalName 为修改申请的原记录名称（审核列表显示「修改自：xxx」用，无原记录时 null）；
 * companyId 为关联会社 id（companies 表，无外键约束，null=未关联会社；关联时 staff 即该公司名快照）。
 * tags 为标签简要数组（{id,name,category,spoiler_level}，仅 approved 标签；galgame_count 详情页由 findTagsByGalgame 附全站计数）。
 * categories 为旧分类系统（galgame_tags 表）的 section_key 字符串数组（24 个 gg-*，与 tags 标签实体系统完全独立）。
 */
public record Galgame(
        Long id,
        String name,
        String description,
        String image,
        String staff,
        Long companyId,
        List<Link> links,
        List<String> categories,
        List<GalgameTagBrief> tags,
        Long createdBy,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        Long viewCount,
        LocalDate releaseDate,
        Double ratingAvg,
        Long ratingCount,
        String status,
        String rejectReason,
        LocalDateTime reviewedAt,
        String creator,
        String applyType,
        Long originalId,
        String originalName) {

    /** 资源链接：[{label,url}] */
    public record Link(String label, String url) {
    }
}
