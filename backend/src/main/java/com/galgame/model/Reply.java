package com.galgame.model;

import java.time.LocalDateTime;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 回复实体，对应 replies 表。
 * liked 为 null 时序列化省略（如创建回复的响应不含 liked）；
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
        Boolean liked) {

    /** 核心回复（liked 未知），DAO 查询时使用 */
    public static Reply core(Long id, Long userId, Long postId, Long parentId, String parentAuthor,
                             String author, String content, LocalDateTime createdAt, int likeCount) {
        return new Reply(id, userId, postId, parentId, parentAuthor, author, content, createdAt, likeCount, null);
    }

    public Reply withLiked(boolean liked) {
        return new Reply(id, userId, postId, parentId, parentAuthor, author, content, createdAt, likeCount, liked);
    }
}
