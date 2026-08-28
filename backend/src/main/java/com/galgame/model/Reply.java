package com.galgame.model;

import java.time.LocalDateTime;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 回复实体，对应 replies 表。
 * liked / disliked 为 null 时序列化省略（如创建回复的响应不含 liked）；
 * userId 为 null（老回复）时序列化省略。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Reply(
        Long id,
        Long userId,
        Long postId,
        Long parentId,
        String parentAuthor,
        String author,
        String content,
        LocalDateTime createdAt,
        int likeCount,
        int dislikeCount,
        @JsonProperty("is_pinned") boolean pinned,
        Boolean liked,
        Boolean disliked) {

    /** 核心回复（liked / disliked 未知），DAO 查询时使用 */
    public static Reply core(Long id, Long userId, Long postId, Long parentId, String parentAuthor,
                             String author, String content, LocalDateTime createdAt,
                             int likeCount, int dislikeCount, boolean pinned) {
        return new Reply(id, userId, postId, parentId, parentAuthor, author, content, createdAt,
                likeCount, dislikeCount, pinned, null, null);
    }

    /** 附加赞/踩状态（详情页由 PostController 传入，前端需要 liked 与 disliked） */
    public Reply withLikeState(boolean liked, boolean disliked) {
        return new Reply(id, userId, postId, parentId, parentAuthor, author, content, createdAt,
                likeCount, dislikeCount, pinned, liked, disliked);
    }

    public Reply withLiked(boolean liked) {
        return new Reply(id, userId, postId, parentId, parentAuthor, author, content, createdAt,
                likeCount, dislikeCount, pinned, liked, disliked);
    }
}
