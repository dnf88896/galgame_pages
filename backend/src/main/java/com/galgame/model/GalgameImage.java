package com.galgame.model;

/**
 * Galgame 画廊图片，对应 galgame_images 表。
 * <p>json 输出为 snake_case（由全局 Jackson 配置控制）；id 为自增主键，url 为图片相对 URL（/uploads/galgame_images/...）。
 * sort_order / created_at 不返回到前端（前端只需按数组顺序展示）。
 */
public record GalgameImage(Long id, String url) {
}
