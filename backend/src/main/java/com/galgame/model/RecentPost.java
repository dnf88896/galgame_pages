package com.galgame.model;

import java.time.LocalDateTime;

/** 用户公开资料里的最近发帖条目。 */
public record RecentPost(Long id, String title, LocalDateTime createdAt) {
}
