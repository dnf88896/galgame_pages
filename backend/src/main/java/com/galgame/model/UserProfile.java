package com.galgame.model;

import java.time.LocalDateTime;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 用户公开资料（GET /api/users/{id}）。
 * <p>字段扁平化输出：id / username / avatar_url / bio / created_at / admin_level / post_count /
 * reply_count / received_likes / recent_posts[] / follower_count /
 * following_count / is_following / is_blocked / favorite_count / hide_favorites / ban_until。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record UserProfile(
        Long id,
        String username,
        String avatarUrl,
        String bio,
        LocalDateTime createdAt,
        int adminLevel,
        int postCount,
        int replyCount,
        int receivedLikes,
        List<RecentPost> recentPosts,
        int followerCount,
        int followingCount,
        boolean isFollowing,
        boolean isBlocked,
        int favoriteCount,
        int hideFavorites,
        LocalDateTime banUntil) {
}
