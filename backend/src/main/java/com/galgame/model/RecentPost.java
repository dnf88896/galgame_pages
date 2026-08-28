package com.galgame.model;

import java.time.LocalDateTime;

/** 用户公开资料里的最近发帖条目。pinnedUntil 供资料页显示置顶标签（可空）。 */
public record RecentPost(Long id, String title, LocalDateTime createdAt, LocalDateTime pinnedUntil) {
}
