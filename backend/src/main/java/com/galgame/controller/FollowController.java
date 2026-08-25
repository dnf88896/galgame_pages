package com.galgame.controller;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.galgame.auth.TokenService;
import com.galgame.dao.FollowDao;
import com.galgame.dao.UserDao;
import com.galgame.model.User;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 关注功能接口。登录路径不在鉴权拦截器内，需登录的接口手动解析 token。
 */
@RestController
@RequestMapping("/api/users")
public class FollowController {

    private final FollowDao followDao;
    private final UserDao userDao;
    private final TokenService tokenService;

    public FollowController(FollowDao followDao, UserDao userDao, TokenService tokenService) {
        this.followDao = followDao;
        this.userDao = userDao;
        this.tokenService = tokenService;
    }

    /** 关注/取关 toggle（需登录，手动鉴权） */
    @PostMapping("/{id}/follow")
    public ResponseEntity<Object> toggleFollow(@PathVariable Long id, HttpServletRequest request) {
        Optional<Long> uid = tokenService.resolveUserId(request.getHeader("Authorization"));
        if (uid.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "请先登录。"));
        }
        long followerId = uid.get();
        if (userDao.findById(id).isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "用户不存在。"));
        }
        if (followerId == id) {
            return ResponseEntity.badRequest().body(Map.of("error", "不能关注自己。"));
        }
        boolean following = followDao.toggle(followerId, id);
        int followerCount = followDao.countFollowers(id);
        return ResponseEntity.ok(Map.<String, Object>of(
                "following", following,
                "follower_count", followerCount));
    }

    /** 该用户关注的人（公开，可选登录判断 is_following） */
    @GetMapping("/{id}/following")
    public ResponseEntity<Object> listFollowing(@PathVariable Long id, HttpServletRequest request) {
        if (userDao.findById(id).isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "用户不存在。"));
        }
        Optional<Long> currentUserId = tokenService.resolveUserId(request.getHeader("Authorization"));
        return ResponseEntity.ok(toFollowResponse(followDao.findFollowing(id), currentUserId));
    }

    /** 关注该用户的粉丝（公开，可选登录判断 is_following） */
    @GetMapping("/{id}/followers")
    public ResponseEntity<Object> listFollowers(@PathVariable Long id, HttpServletRequest request) {
        if (userDao.findById(id).isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "用户不存在。"));
        }
        Optional<Long> currentUserId = tokenService.resolveUserId(request.getHeader("Authorization"));
        return ResponseEntity.ok(toFollowResponse(followDao.findFollowers(id), currentUserId));
    }

    /** 把 User 列表映射成公开的 follow 响应 shape（avatar_url/bio 可能为 null，用 HashMap 保留） */
    private List<Map<String, Object>> toFollowResponse(List<User> users, Optional<Long> currentUserId) {
        return users.stream()
                .map(u -> {
                    Map<String, Object> m = new HashMap<>();
                    m.put("id", u.id());
                    m.put("username", u.username());
                    m.put("avatar_url", u.avatarUrl());
                    m.put("bio", u.bio());
                    m.put("is_following", currentUserId.isPresent()
                            && followDao.isFollowing(currentUserId.get(), u.id()));
                    return m;
                })
                .toList();
    }
}
