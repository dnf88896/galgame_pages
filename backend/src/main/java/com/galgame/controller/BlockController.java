package com.galgame.controller;

import java.util.Map;
import java.util.Optional;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.galgame.auth.TokenService;
import com.galgame.dao.BlockDao;
import com.galgame.dao.UserDao;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 屏蔽功能接口。登录路径不在鉴权拦截器内，需登录的接口手动解析 token。
 */
@RestController
@RequestMapping("/api/users")
public class BlockController {

    private final BlockDao blockDao;
    private final UserDao userDao;
    private final TokenService tokenService;

    public BlockController(BlockDao blockDao, UserDao userDao, TokenService tokenService) {
        this.blockDao = blockDao;
        this.userDao = userDao;
        this.tokenService = tokenService;
    }

    /** 屏蔽/取消屏蔽 toggle（需登录，手动鉴权） */
    @PostMapping("/{id}/block")
    public ResponseEntity<Object> toggleBlock(@PathVariable Long id, HttpServletRequest request) {
        Optional<Long> uid = tokenService.resolveUserId(request.getHeader("Authorization"));
        if (uid.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "请先登录。"));
        }
        long blockerId = uid.get();
        if (userDao.findById(id).isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "用户不存在。"));
        }
        if (blockerId == id) {
            return ResponseEntity.badRequest().body(Map.of("error", "不能屏蔽自己。"));
        }
        boolean blocked = blockDao.toggle(blockerId, id);
        return ResponseEntity.ok(Map.<String, Object>of("blocked", blocked));
    }
}
