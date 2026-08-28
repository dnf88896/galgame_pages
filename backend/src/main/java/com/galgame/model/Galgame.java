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
 * rejectReason 为拒绝理由（仅 rejected 时有值），reviewedAt 为审核时间，creator 为提交人昵称（昵称优先、账号名兜底）。
 */
public record Galgame(
        Long id,
        String name,
        String description,
        String image,
        String staff,
        List<Link> links,
        List<String> tags,
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
        String creator) {

    /** 资源链接：[{label,url}] */
    public record Link(String label, String url) {
    }
}
