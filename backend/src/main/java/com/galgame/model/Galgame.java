package com.galgame.model;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Galgame 作品实体，对应 galgames 表。
 * <p>json 输出为 snake_case（由全局 Jackson 配置控制）；
 * links / tags 恒非 null（无数据时为空数组）；createdBy 在作者被删除后为 null。
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
        LocalDateTime updatedAt) {

    /** 资源链接：[{label,url}] */
    public record Link(String label, String url) {
    }
}
