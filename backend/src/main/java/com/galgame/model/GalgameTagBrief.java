package com.galgame.model;

/**
 * Galgame 标签简要信息：Galgame 详情/列表里标签数组元素（{id, name, category, spoiler_level, galgame_count}）。
 * <p>json 输出为 snake_case（由全局 Jackson 配置控制）：spoilerLevel → spoiler_level、galgameCount → galgame_count；
 * galgame_count 为全站该标签下已上架（status='approved'）作品数，列表接口可不含（null 时序列化省略，见调用方）。
 */
public record GalgameTagBrief(Long id, String name, String category, Integer spoilerLevel, Integer galgameCount) {
}
