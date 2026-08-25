package com.galgame.controller;

import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.galgame.dao.PostDao;

/**
 * 首页左侧分区栏相关公开接口。
 */
@RestController
@RequestMapping("/api/boards")
public class BoardController {

    private final PostDao postDao;

    public BoardController(PostDao postDao) {
        this.postDao = postDao;
    }

    /** 各分区帖子数，返回 [{name, count}]，仅供分区栏展示，不含帖子内容 */
    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> counts() {
        return ResponseEntity.ok(postDao.countByCategory());
    }
}
