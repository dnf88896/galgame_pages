package com.galgame.model;

import java.util.ArrayList;
import java.util.List;

/**
 * 帖子列表的过滤条件。q/category 为 null 表示「不过滤」，sections 为空列表表示「不过滤」。
 * <p>筛选逻辑以后会持续扩展（作者、时间范围、排序、标签等）。
 * 新增维度时：在此追加一个字段，在 {@code PostDao.findAll} 的条件拼接里加一段即可，
 * 接口签名（/api/posts?q=&category=&sort=）与前端调用方式保持不变。
 * <p>sections 支持多标签 AND 过滤：帖子须同时拥有所有指定标签，空列表不过滤。
 * <p>sort 取值：time（默认，时间倒序）/ hot（热度：view+reply*3+like*5 倒序）/ likes（点赞量倒序）/ following（只看我关注的人）。
 */
public record PostFilter(String q, String category, List<String> sections, String sort) {

    /** 构造并清洗输入：q/category/sort 空白归 null；section 单值与 sections 列表都并入 sections（AND 语义），空列表不过滤 */
    public static PostFilter of(String q, String category, String section, List<String> sections, String sort) {
        List<String> cleaned = new ArrayList<>();
        if (sections != null) {
            for (String s : sections) {
                if (s != null && !s.isBlank()) cleaned.add(s.trim());
            }
        } else if (section != null && !section.isBlank()) {
            cleaned.add(section.trim());
        }
        return new PostFilter(trimToNull(q), trimToNull(category), List.copyOf(cleaned), trimToNull(sort));
    }

    private static String trimToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }
}
