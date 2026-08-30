package com.galgame.service;

import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.galgame.dao.BlockDao;
import com.galgame.dao.FollowDao;
import com.galgame.dao.PostDao;
import com.galgame.dao.ReplyDao;
import com.galgame.dao.UserDao;
import com.galgame.exception.BusinessException;
import com.galgame.model.Post;
import com.galgame.model.RecentPost;
import com.galgame.model.User;
import com.galgame.model.UserProfile;

/**
 * 用户业务层：搜索 / 排行 / 公开资料 / 收藏列表 / 解封。
 * <p>业务失败统一抛 {@link BusinessException}，由 Controller 捕获后转为对应 HTTP 响应；
 * 当前登录用户 id 由 Controller 从 Authorization 头解析后传入，本层不接触 HttpServletRequest。
 */
@Service
public class UserService {

    private final UserDao userDao;
    private final PostDao postDao;
    private final ReplyDao replyDao;
    private final FollowDao followDao;
    private final BlockDao blockDao;

    public UserService(UserDao userDao, PostDao postDao, ReplyDao replyDao,
                       FollowDao followDao, BlockDao blockDao) {
        this.userDao = userDao;
        this.postDao = postDao;
        this.replyDao = replyDao;
        this.followDao = followDao;
        this.blockDao = blockDao;
    }

    /** 按用户名/昵称模糊搜索用户；kw 为 null 或空白返回空列表（最多 50 条，新注册优先） */
    public List<User> searchUsers(String kw) {
        String keyword = kw == null ? "" : kw.trim();
        if (keyword.isEmpty()) {
            return List.of();
        }
        return userDao.findByNameLike(keyword);
    }

    /** 用户排行：按萌点从高到低分页返回；page/page_size 越界自动回退（默认 1/20，page_size≤100） */
    public Map<String, Object> ranking(int page, int pageSize) {
        if (page < 1) {
            page = 1;
        }
        if (pageSize < 1 || pageSize > 100) {
            pageSize = 20;
        }
        int total = userDao.countAll();
        List<User> items = userDao.findAllOrderByMoe(pageSize, (page - 1) * pageSize);
        return Map.of("total", total, "items", items);
    }

    /**
     * 公开资料：基础信息 + 发帖/回复/收到赞统计 + 最新 5 帖 + 关注/粉丝数 + is_following + is_blocked。
     * currentUserId 为当前登录用户 id（未登录传 null）。
     */
    public UserProfile getProfile(Long id, Long currentUserId) {
        User user = userDao.findById(id)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "用户不存在。"));
        int postCount = postDao.countByUserId(id);
        int replyCount = replyDao.countByUserId(id);
        int receivedLikes = postDao.countLikesReceived(id) + replyDao.countLikesReceived(id);

        // 未登录视为未关注 / 未屏蔽
        boolean isFollowing = currentUserId != null && followDao.isFollowing(currentUserId, id);
        boolean isBlocked = currentUserId != null && blockDao.isBlocked(currentUserId, id);

        // 双方任一方向存在屏蔽关系时，隐藏该用户「最近发布」列表
        boolean hidden = currentUserId != null
                && (blockDao.isBlocked(currentUserId, id) || blockDao.isBlocked(id, currentUserId));
        List<RecentPost> recentPosts = hidden ? List.of() : postDao.findRecentPostsByUser(id, 5);

        return new UserProfile(
                user.id(), user.username(), user.nickname(), user.avatarUrl(), user.bio(), user.createdAt(),
                user.adminLevel(), postCount, replyCount, receivedLikes, recentPosts,
                followDao.countFollowers(id), followDao.countFollowing(id), isFollowing, isBlocked,
                postDao.countFavorites(id), user.hideFavorites(), user.moePoints(), user.banUntil());
    }

    /** 收藏列表；若用户隐藏收藏则仅本人可见（currentUserId 为空或非本人抛 403） */
    public List<Post> getFavorites(Long id, Long currentUserId) {
        User user = userDao.findById(id)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "用户不存在。"));
        if (user.hideFavorites() != null && user.hideFavorites() == 1) {
            boolean isSelf = currentUserId != null && currentUserId.equals(id);
            if (!isSelf) {
                throw new BusinessException(HttpStatus.FORBIDDEN, "该用户已隐藏收藏。");
            }
        }
        return postDao.findFavoritesByUser(id);
    }

    /** 解封用户（需登录 + 管理员）：清除 ban_until */
    public void unban(Long id, long currentUserId) {
        User current = userDao.findById(currentUserId)
                .orElseThrow(() -> new IllegalStateException("登录用户不存在"));
        if (current.adminLevel() == null || current.adminLevel() < 1) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "需要管理员权限。");
        }
        if (userDao.findById(id).isEmpty()) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "用户不存在。");
        }
        userDao.updateBanUntil(id, null);
    }
}
