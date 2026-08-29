package com.galgame.model;

import java.time.LocalDate;
import java.util.List;

/**
 * Galgame 创建/编辑请求体经 {@code GalgameController.normalizeAndValidate} 校验后的规范化结果
 * （create 创建、update 原地编辑、修改申请影子行 createUpdateShadow 三处共用）。
 * <p>companyId 非空时 staff 已被覆盖为对应会社名称；categories 为校验整理后的分类 section_key 列表（去重保序）；
 * tagIds / relatedIds 为校验整理后的 id 列表（去重保序）；staffLinks / characterLinks 为校验整理后的关联列表。
 * <p>此 record 不直接对外序列化，仅作为 DAO 层入参（独立成顶层类，方便 GalgameDao 引用）。
 */
public record NormalizedGalgameRequest(
        String name,
        String description,
        String image,
        String staff,
        Long companyId,
        LocalDate releaseDate,
        List<Galgame.Link> links,
        List<String> categories,
        List<Long> tagIds,
        List<Long> relatedIds,
        List<GalgameStaffLink> staffLinks,
        List<GalgameCharacterLink> characterLinks) {
}
