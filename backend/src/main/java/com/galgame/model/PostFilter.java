package com.galgame.model;

/**
 * 帖子列表的过滤条件。字段为 null 表示「不过滤」。
 * <p>筛选逻辑以后会持续扩展（作者、时间范围、排序、标签等）。
 * 新增维度时：在此追加一个字段，在 {@code PostDao.findAll} 的条件拼接里加一段即可，
 * 接口签名（/api/posts?q=&category=）与前端调用方式保持不变。
 */
public record PostFilter(String q, String category, String section) {

    /** 构造并清洗输入：null / 空白一律归一到 null */
    public static PostFilter of(String q, String category, String section) {
        return new PostFilter(trimToNull(q), trimToNull(category), trimToNull(section));
    }

    private static String trimToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }
}
