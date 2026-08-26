package com.galgame.model;

import java.time.LocalDateTime;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 帖子实体，对应 posts 表。
 * <p>json 输出为 snake_case（由全局 Jackson 配置控制）；
 * liked / favorited / replies 为 null 时序列化省略（列表接口不含这三项）；
 * userId 为 null（老帖子）时序列化省略。
 * tags 恒非 null（无标签时为空数组，始终序列化）。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Post(
        Long id,
        Long userId,
        String author,
        String category,
        List<String> tags,
        String title,
        String content,
        LocalDateTime createdAt,
        int replyCount,
        int viewCount,
        int likeCount,
        Boolean liked,
        Boolean favorited,
        List<Attachment> attachments,
        List<Reply> replies) {

    /** 核心帖子（不含上下文），DAO 查询基础列时使用 */
    public static Post core(Long id, Long userId, String author, String category, List<String> tags,
                            String title, String content, LocalDateTime createdAt,
                            int replyCount, int viewCount, int likeCount) {
        return new Post(id, userId, author, category, tags, title, content, createdAt,
                replyCount, viewCount, likeCount, null, null, null, null);
    }

    /** 附加标签数组（DAO 批量加载 tags 后调用），保留其余字段 */
    public Post withTags(List<String> tags) {
        return new Post(id, userId, author, category, tags, title, content, createdAt,
                replyCount, viewCount, likeCount, liked, favorited, attachments, replies);
    }

    /** 附加上下文（点赞/收藏状态 / 附件 / 回复）得到对外完整对象 */
    public Post withContext(Boolean liked, Boolean favorited, List<Attachment> attachments, List<Reply> replies) {
        return new Post(id, userId, author, category, tags, title, content, createdAt,
                replyCount, viewCount, likeCount, liked, favorited, attachments, replies);
    }
}
