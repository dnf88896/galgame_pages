package com.galgame.model;

import java.time.LocalDateTime;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Galgame 详情页评论实体，对应 galgame_replies 表。
 * <p>仿 {@link Reply}，去掉置顶：无 is_pinned 字段。
 * liked / disliked 为 null 时序列化省略（如创建评论的响应不含 liked）；
 * userId 为 null（老评论/匿名）时序列化省略；
 * images 为 null 时序列化省略（无图片的评论不含 images 键）；
 * rating 为 null 时序列化省略（评论作者对该 galgame 无评分时不输出）；
 * isLong 恒非 null（DB 列 NOT NULL DEFAULT 0，短评 false / 长评 true，JSON 键 is_long 始终输出）。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record GalgameReply(
        Long id,
        Long galgameId,
        Long userId,
        Long parentId,
        String parentAuthor,
        String author,
        String content,
        Boolean isLong,
        List<String> images,
        LocalDateTime createdAt,
        int likeCount,
        int dislikeCount,
        Boolean liked,
        Boolean disliked,
        Double rating) {

    /** 核心评论（liked / disliked / rating 未知），DAO 查询时使用 */
    public static GalgameReply core(Long id, Long galgameId, Long userId, Long parentId, String parentAuthor,
                                    String author, String content, Boolean isLong, List<String> images, LocalDateTime createdAt,
                                    int likeCount, int dislikeCount) {
        return new GalgameReply(id, galgameId, userId, parentId, parentAuthor, author, content, isLong, images, createdAt,
                likeCount, dislikeCount, null, null, null);
    }

    /** 附加赞/踩状态（详情页由 Controller 传入，前端需要 liked 与 disliked） */
    public GalgameReply withLikeState(boolean liked, boolean disliked) {
        return new GalgameReply(id, galgameId, userId, parentId, parentAuthor, author, content, isLong, images, createdAt,
                likeCount, dislikeCount, liked, disliked, rating);
    }

    /** 附加评论作者的 galgame 评分（详情页由 Controller 注入，无评分传 null） */
    public GalgameReply withRating(Double rating) {
        return new GalgameReply(id, galgameId, userId, parentId, parentAuthor, author, content, isLong, images, createdAt,
                likeCount, dislikeCount, liked, disliked, rating);
    }
}
