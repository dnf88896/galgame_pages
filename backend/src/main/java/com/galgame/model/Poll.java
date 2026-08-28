package com.galgame.model;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 帖子投票实体，对应 polls 表。
 * <p>json 输出为 snake_case（由全局 Jackson 配置控制）。
 * user / options / has_voted / vote_count / voters / voters_count 为 null 时序列化省略：
 * DAO 基础查询不含这些字段；列表接口由 canViewResults 决定是否填充（结果不可见时
 * options 不带票数、voters 省略、vote_count 省略）。
 * <p>vote_count（总票数）用 Integer 可空——结果不可见时为 null（省略），
 * 前端按 undefined 处理不显示进度条。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Poll(
        Long id,
        String title,
        String description,
        String type,
        Integer minChoice,
        Integer maxChoice,
        LocalDateTime deadline,
        String status,
        String resultVisibility,
        Boolean isAnonymous,
        Boolean canChangeVote,
        Long postId,
        Long userId,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        Map<String, Object> user,
        List<PollOption> options,
        Boolean hasVoted,
        Integer voteCount,
        List<Map<String, Object>> voters,
        Integer votersCount) {

    /** 核心投票（不含上下文），DAO 查询基础列时使用 */
    public static Poll core(Long id, String title, String description, String type,
                            Integer minChoice, Integer maxChoice, LocalDateTime deadline,
                            String status, String resultVisibility, Boolean isAnonymous,
                            Boolean canChangeVote, Long postId, Long userId,
                            LocalDateTime createdAt, LocalDateTime updatedAt) {
        return new Poll(id, title, description, type, minChoice, maxChoice, deadline, status,
                resultVisibility, isAnonymous, canChangeVote, postId, userId, createdAt, updatedAt,
                null, null, null, null, null, null);
    }

    /** 附加上下文（楼主信息/选项/当前用户是否已投/结果可见时的票数统计），得到对外完整对象 */
    public Poll withContext(Map<String, Object> user, List<PollOption> options, Boolean hasVoted,
                            Integer voteCount, List<Map<String, Object>> voters, Integer votersCount) {
        return new Poll(id, title, description, type, minChoice, maxChoice, deadline, status,
                resultVisibility, isAnonymous, canChangeVote, postId, userId, createdAt, updatedAt,
                user, options, hasVoted, voteCount, voters, votersCount);
    }
}
