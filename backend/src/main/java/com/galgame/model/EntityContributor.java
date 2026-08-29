package com.galgame.model;

import java.time.LocalDateTime;

/**
 * 条目贡献者，对应 entity_contributors 表 JOIN users 的查询结果（非独立表行，无自增主键）。
 * <p>json 输出为 snake_case（由全局 Jackson 配置控制）；
 * id 为用户 id，username 为账号名，nickname 为昵称（旧数据可能为 null，前端兜底），
 * avatarUrl 为头像 URL（可为 null），createdAt 为贡献时间。
 */
public record EntityContributor(
        Long id,
        String username,
        String nickname,
        String avatarUrl,
        LocalDateTime createdAt) {
}
