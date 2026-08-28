package com.galgame.controller;

import java.util.List;
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
import com.galgame.dao.BlockDao;
import com.galgame.dao.FollowDao;
import com.galgame.dao.PostDao;
import com.galgame.dao.ReplyDao;
import com.galgame.dao.UserDao;
import com.galgame.model.RecentPost;
import com.galgame.model.User;
import com.galgame.model.UserProfile;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 用户公开资料接口（无需登录，可选登录判断 is_following）。
 */
@RestController
@RequestMapping("/api/users")
public class UserController {

    private final UserDao userDao;
    private final PostDao postDao;
    private final ReplyDao replyDao;
    private final FollowDao followDao;
    private final BlockDao blockDao;
    private final TokenService tokenService;

    public UserController(UserDao userDao, PostDao postDao, ReplyDao replyDao,
                          FollowDao followDao, BlockDao blockDao, TokenService tokenService) {
        this.userDao = userDao;
        this.postDao = postDao;
        this.replyDao = replyDao;
        this.followDao = followDao;
        this.blockDao = blockDao;
        this.tokenService = tokenService;
    }

    /** 按用户名模糊搜索用户（公开，无需登录）；返回用户列表（最多 50 条，新注册优先），q 为空返回空列表 */
    @GetMapping("/search")
    public ResponseEntity<Object> searchUsers(@RequestParam(value = "q", required = false) String q) {
        String kw = q == null ? "" : q.trim();
        if (kw.isEmpty()) {
            return ResponseEntity.ok(List.of());
        }
        return ResponseEntity.ok(userDao.findByNameLike(kw));
    }

    /** 3. 公开资料：基础信息 + 发帖/回复/收到赞统计 + 最新 5 帖 + 关注/粉丝数 + is_following + is_blocked */
    @GetMapping("/{id}")
    public ResponseEntity<Object> getUser(@PathVariable Long id, HttpServletRequest request) {
        Optional<User> opt = userDao.findById(id);
        if (opt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "用户不存在。"));
        }
        User user = opt.get();
        int postCount = postDao.countByUserId(id);
        int replyCount = replyDao.countByUserId(id);
        int receivedLikes = postDao.countLikesReceived(id) + replyDao.countLikesReceived(id);

        // 未登录视为未关注 / 未屏蔽
        Optional<Long> currentUserId = tokenService.resolveUserId(request.getHeader("Authorization"));
        boolean isFollowing = currentUserId.isPresent() && followDao.isFollowing(currentUserId.get(), id);
        boolean isBlocked = currentUserId.isPresent() && blockDao.isBlocked(currentUserId.get(), id);

        // 双方任一方向存在屏蔽关系时，隐藏该用户「最近发布」列表
        boolean hidden = currentUserId.isPresent()
                && (blockDao.isBlocked(currentUserId.get(), id) || blockDao.isBlocked(id, currentUserId.get()));
        List<RecentPost> recentPosts = hidden ? List.of() : postDao.findRecentPostsByUser(id, 5);

        UserProfile profile = new UserProfile(
                user.id(), user.username(), user.nickname(), user.avatarUrl(), user.bio(), user.createdAt(),
                user.adminLevel(), postCount, replyCount, receivedLikes, recentPosts,
                followDao.countFollowers(id), followDao.countFollowing(id), isFollowing, isBlocked,
                postDao.countFavorites(id), user.hideFavorites(), user.moePoints(), user.banUntil());
        return ResponseEntity.ok(profile);
    }

    /** 收藏列表（公开；若用户隐藏收藏则仅本人可见，未登录/非本人访问返回 403） */
    @GetMapping("/{id}/favorites")
    public ResponseEntity<Object> getFavorites(@PathVariable Long id, HttpServletRequest request) {
        Optional<User> opt = userDao.findById(id);
        if (opt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "用户不存在。"));
        }
        User user = opt.get();
        if (user.hideFavorites() != null && user.hideFavorites() == 1) {
            Optional<Long> currentUserId = tokenService.resolveUserId(request.getHeader("Authorization"));
            boolean isSelf = currentUserId.isPresent() && currentUserId.get().equals(id);
            if (!isSelf) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "该用户已隐藏收藏。"));
            }
        }
        return ResponseEntity.ok(postDao.findFavoritesByUser(id));
    }

    /** 解封用户（需登录 + 管理员）：清除 ban_until */
    @PostMapping("/{id}/unban")
    public ResponseEntity<Object> unban(@PathVariable Long id, HttpServletRequest request) {
        long currentUserId = AuthContext.currentUserId(request);
        User current = userDao.findById(currentUserId).orElseThrow(() -> new IllegalStateException("登录用户不存在"));
        if (current.adminLevel() == null || current.adminLevel() < 1) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "需要管理员权限。"));
        }
        if (userDao.findById(id).isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "用户不存在。"));
        }
        userDao.updateBanUntil(id, null);
        return ResponseEntity.ok(Map.of("ok", true));
    }
}
