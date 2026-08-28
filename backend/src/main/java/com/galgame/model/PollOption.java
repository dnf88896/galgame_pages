package com.galgame.model;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 投票选项实体，对应 poll_options 表。
 * <p>vote_count 用 Integer 可空——结果不可见时为 null（省略，前端按 undefined 处理）；
 * is_voted 表示当前查看者是否选过该项（未登录恒 false）。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PollOption(
        Long id,
        String text,
        Integer voteCount,
        Boolean isVoted) {

    /** 核心选项（is_voted 未知），DAO 查询时使用 */
    public static PollOption core(Long id, String text, Integer voteCount) {
        return new PollOption(id, text, voteCount, null);
    }

    /** 附加视图状态：当前用户是否选过该项 + 结果是否可见决定票数（不可见传 null） */
    public PollOption withView(boolean isVoted, Integer voteCount) {
        return new PollOption(id, text, voteCount, isVoted);
    }
}
