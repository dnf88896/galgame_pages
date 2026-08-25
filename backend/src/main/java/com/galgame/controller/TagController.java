package com.galgame.controller;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.galgame.constants.TagConstants;

/**
 * 标签接口（kungal 风格）：三大类 + 小分支，附带各标签帖子计数。
 * <p>公开接口，无需登录；路由未注册到鉴权拦截器。
 */
@RestController
@RequestMapping("/api/tags")
public class TagController {

    private final JdbcTemplate jdbcTemplate;

    public TagController(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 标签树：{ categories: [{ key, label, description, post_count, sections: [{ key, label, post_count }] }] }。
     * 计数来自 posts 表实际数据；无帖子的 section post_count 为 0。
     */
    @GetMapping
    public ResponseEntity<Object> tags() {
        Map<String, Long> sectionCount = countSection();
        Map<String, Long> categoryCount = countCategoryFromSections(sectionCount);

        List<Map<String, Object>> categories = TagConstants.categories().stream()
                .map(c -> {
                    Map<String, Object> categoryMap = new LinkedHashMap<>();
                    categoryMap.put("key", c.key());
                    categoryMap.put("label", c.label());
                    categoryMap.put("description", c.description());
                    categoryMap.put("post_count", categoryCount.getOrDefault(c.key(), 0L));
                    List<Map<String, Object>> sections = c.sections().stream()
                            .map(s -> {
                                Map<String, Object> sectionMap = new LinkedHashMap<>();
                                sectionMap.put("key", s.key());
                                sectionMap.put("label", s.label());
                                sectionMap.put("post_count", sectionCount.getOrDefault(s.key(), 0L));
                                return sectionMap;
                            })
                            .toList();
                    categoryMap.put("sections", sections);
                    return categoryMap;
                })
                .toList();

        return ResponseEntity.ok(Map.of("categories", categories));
    }

    /**
     * 三大类帖子数 = 其下小分支计数之和（小分支 key 前缀决定所属大类）。
     * 分区（category）与标签（section）相互独立，因此大类计数只统计「挂了该组标签的帖子」。
     */
    private Map<String, Long> countCategoryFromSections(Map<String, Long> sectionCount) {
        Map<String, Long> map = new LinkedHashMap<>();
        for (TagConstants.Category c : TagConstants.categories()) {
            long sum = 0L;
            for (TagConstants.Section s : c.sections()) {
                sum += sectionCount.getOrDefault(s.key(), 0L);
            }
            map.put(c.key(), sum);
        }
        return map;
    }

    /** 各小分支帖子数：来自 post_tags 关联表（一帖多标签会分别计数） */
    private Map<String, Long> countSection() {
        return jdbcTemplate.query(
                "SELECT section_key AS k, COUNT(*) AS c FROM post_tags GROUP BY section_key",
                rs -> {
                    Map<String, Long> map = new LinkedHashMap<>();
                    while (rs.next()) {
                        map.put(rs.getString("k"), rs.getLong("c"));
                    }
                    return map;
                });
    }
}
