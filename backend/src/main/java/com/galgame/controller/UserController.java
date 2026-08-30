package com.galgame.controller;

import java.util.Map;
import java.util.Optional;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.galgame.auth.AuthContext;
import com.galgame.auth.TokenService;
import com.galgame.exception.BusinessException;
import com.galgame.service.UserService;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 用户公开资料接口（无需登录，可选登录判断 is_following）。
 * <p>业务逻辑全部委托 {@link UserService}，本层只负责参数解析、登录态解析与业务异常转响应。
 */
@RestController
@RequestMapping("/api/users")
public class UserController {

    private final UserService userService;
    private final TokenService tokenService;

    public UserController(UserService userService, TokenService tokenService) {
        this.userService = userService;
        this.tokenService = tokenService;
    }

    /** 按用户名模糊搜索用户（公开，无需登录）；返回用户列表（最多 50 条，新注册优先），q 为空返回空列表 */
    @GetMapping("/search")
    public ResponseEntity<Object> searchUsers(@RequestParam(value = "q", required = false) String q) {
        try {
            return ResponseEntity.ok(userService.searchUsers(q));
        } catch (BusinessException e) {
            return ResponseEntity.status(e.getStatus()).body(Map.of("error", e.getMessage()));
        }
    }

    /** 用户排行：按萌点从高到低分页返回（公开，无需登录）；page/page_size 越界自动回退（默认 1/20，page_size≤100） */
    @GetMapping("/ranking")
    public ResponseEntity<Object> userRanking(
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "page_size", defaultValue = "20") int pageSize) {
        try {
            return ResponseEntity.ok(userService.ranking(page, pageSize));
        } catch (BusinessException e) {
            return ResponseEntity.status(e.getStatus()).body(Map.of("error", e.getMessage()));
        }
    }

    /** 3. 公开资料：基础信息 + 发帖/回复/收到赞统计 + 最新 5 帖 + 关注/粉丝数 + is_following + is_blocked */
    @GetMapping("/{id}")
    public ResponseEntity<Object> getUser(@PathVariable Long id, HttpServletRequest request) {
        try {
            Optional<Long> currentUserId = tokenService.resolveUserId(request.getHeader("Authorization"));
            return ResponseEntity.ok(userService.getProfile(id, currentUserId.orElse(null)));
        } catch (BusinessException e) {
            return ResponseEntity.status(e.getStatus()).body(Map.of("error", e.getMessage()));
        }
    }

    /** 收藏列表（公开；若用户隐藏收藏则仅本人可见，未登录/非本人访问返回 403） */
    @GetMapping("/{id}/favorites")
    public ResponseEntity<Object> getFavorites(@PathVariable Long id, HttpServletRequest request) {
        try {
            Optional<Long> currentUserId = tokenService.resolveUserId(request.getHeader("Authorization"));
            return ResponseEntity.ok(userService.getFavorites(id, currentUserId.orElse(null)));
        } catch (BusinessException e) {
            return ResponseEntity.status(e.getStatus()).body(Map.of("error", e.getMessage()));
        }
    }

    /** 解封用户（需登录 + 管理员）：清除 ban_until */
    @PostMapping("/{id}/unban")
    public ResponseEntity<Object> unban(@PathVariable Long id, HttpServletRequest request) {
        try {
            long currentUserId = AuthContext.currentUserId(request);
            userService.unban(id, currentUserId);
            return ResponseEntity.ok(Map.of("ok", true));
        } catch (BusinessException e) {
            return ResponseEntity.status(e.getStatus()).body(Map.of("error", e.getMessage()));
        }
    }
}
