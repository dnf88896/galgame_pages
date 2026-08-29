package com.galgame.controller;

import java.util.Map;
import java.util.Set;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.galgame.dao.EntityContributorDao;

/**
 * 条目贡献者查询接口（公开）：GET /api/entity-contributors?entry_type=galgame&entry_id=1。
 * <p>路径未注册到鉴权拦截器，本接口不校验登录——贡献者列表是公开展示信息（无需 token）。
 * <p>entry_type 白名单：galgame / company / staff / character；非法返回 400「无效的条目类型。」；
 * entry_id 缺失返回 400「缺少条目 id。」。
 */
@RestController
@RequestMapping("/api/entity-contributors")
public class EntityContributorController {

    private static final Set<String> ALLOWED_TYPES = Set.of("galgame", "company", "staff", "character");

    private final EntityContributorDao entityContributorDao;

    public EntityContributorController(EntityContributorDao entityContributorDao) {
        this.entityContributorDao = entityContributorDao;
    }

    /** 查询条目贡献者列表（公开）：?entry_type= 条目类型 &entry_id= 条目 id */
    @GetMapping
    public ResponseEntity<Object> list(@RequestParam String entry_type,
                                       @RequestParam(required = false) Long entry_id) {
        if (!ALLOWED_TYPES.contains(entry_type)) {
            return ResponseEntity.badRequest().body(Map.of("error", "无效的条目类型。"));
        }
        if (entry_id == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "缺少条目 id。"));
        }
        return ResponseEntity.ok(entityContributorDao.findContributors(entry_type, entry_id));
    }
}
