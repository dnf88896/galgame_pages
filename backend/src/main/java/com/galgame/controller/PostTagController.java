package com.galgame.controller;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.galgame.constants.TagConstants;
import com.galgame.dao.PostDao;

/**
 * 帖子标签树接口（kungal 风格）：四大类 + 小分支，附带各标签的实时帖子计数。
 * <p>公开接口，无需登录；本路径未注册到鉴权拦截器（见 WebConfig）。
 * <p>历史：该实现原在 v0.1.0~v0.1.3 的 TagController 中，v0.1.4 起 TagController 被整体改写成
 * Galgame 标签库（/api/galgame-tags），旧实现被覆盖，导致 /api/tags 自 v0.1.4 起 404
 * （表现为左侧「话题」大类页显示「分类不存在」、帖子卡片标签显示 g-chatting 等原始 key）。
 * 此处按 v0.1.3 的实现恢复，并独立成类以免再次被覆盖。
 */
@RestController
@RequestMapping("/api/tags")
public class PostTagController {

    private final PostDao postDao;

    public PostTagController(PostDao postDao) {
        this.postDao = postDao;
    }

    /**
     * 标签树：{ categories: [{ key, label, description, post_count, sections: [{ key, label, post_count }] }] }。
     * 计数来自 post_tags 表实际数据；无帖子的标签 post_count 为 0。
     */
    @GetMapping
    public ResponseEntity<Object> tags() {
        Map<String, Long> sectionCount = postDao.countBySection();

        List<Map<String, Object>> categories = TagConstants.categories().stream()
                .map(category -> {
                    Map<String, Object> categoryMap = new LinkedHashMap<>();
                    categoryMap.put("key", category.key());
                    categoryMap.put("label", category.label());
                    categoryMap.put("description", category.description());
                    categoryMap.put("post_count", countOfCategory(category, sectionCount));
                    categoryMap.put("sections", category.sections().stream()
                            .map(section -> {
                                Map<String, Object> sectionMap = new LinkedHashMap<>();
                                sectionMap.put("key", section.key());
                                sectionMap.put("label", section.label());
                                sectionMap.put("post_count", sectionCount.getOrDefault(section.key(), 0L));
                                return sectionMap;
                            })
                            .toList());
                    return categoryMap;
                })
                .toList();

        return ResponseEntity.ok(Map.of("categories", categories));
    }

    /**
     * 大类帖子数 = 其下小分支计数之和（一帖在该大类下挂多个标签会分别计入）。
     * 分区（category）与标签（section）相互独立，因此大类计数只统计「挂了该组标签的帖子」。
     */
    private long countOfCategory(TagConstants.Category category, Map<String, Long> sectionCount) {
        long sum = 0L;
        for (TagConstants.Section section : category.sections()) {
            sum += sectionCount.getOrDefault(section.key(), 0L);
        }
        return sum;
    }
}
