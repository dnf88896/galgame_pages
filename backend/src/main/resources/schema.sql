-- 建表脚本：启动时自动执行，CREATE TABLE IF NOT EXISTS 保证幂等。
-- 对已存在的旧库：posts/replies 的 user_id 列、post_likes/reply_likes 的 user_id 结构
-- 由 com.galgame.config.DatabaseMigrator（ApplicationRunner）在启动时条件迁移完成。

CREATE TABLE IF NOT EXISTS users (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    username      VARCHAR(32)  NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    avatar_url    VARCHAR(500) NULL,
    bio           VARCHAR(200) NULL,
    created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_users_username (username)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS auth_tokens (
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    user_id    BIGINT       NOT NULL,
    token_hash CHAR(64)     NOT NULL,
    created_at DATETIME     DEFAULT CURRENT_TIMESTAMP,
    expires_at DATETIME     NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_auth_tokens_hash (token_hash),
    KEY idx_auth_tokens_user_id (user_id),
    CONSTRAINT fk_auth_tokens_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS posts (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    user_id     BIGINT       NULL,
    author      VARCHAR(32)  NOT NULL DEFAULT '匿名',
    category    VARCHAR(32)  NOT NULL DEFAULT '话题',
    title       VARCHAR(80)  NOT NULL,
    content     TEXT         NOT NULL,
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    reply_count INT          NOT NULL DEFAULT 0,
    view_count  INT          NOT NULL DEFAULT 0,
    like_count  INT          NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_posts_user_id (user_id),
    CONSTRAINT fk_posts_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE SET NULL
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

-- 帖子-标签多对多关联：一帖可挂多个小分支标签（posts.section 单值列的替代）。
-- 旧库的 posts.section 数据由 DatabaseMigrator 迁移到本表后删除原列。
CREATE TABLE IF NOT EXISTS post_tags (
    post_id     BIGINT      NOT NULL,
    section_key VARCHAR(32) NOT NULL,
    PRIMARY KEY (post_id, section_key),
    KEY idx_post_tags_section (section_key),
    CONSTRAINT fk_post_tags_post FOREIGN KEY (post_id) REFERENCES posts (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS replies (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    post_id     BIGINT       NOT NULL,
    user_id     BIGINT       NULL,
    author      VARCHAR(32)  NOT NULL DEFAULT '匿名',
    content     TEXT         NOT NULL,
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    like_count  INT          NOT NULL DEFAULT 0,
    parent_id   BIGINT       NULL,
    parent_author VARCHAR(32) NULL COMMENT '父回复作者名快照：父评论删除后子回复仍能显示「回复 @xx」引用',
    PRIMARY KEY (id),
    KEY idx_replies_post_id (post_id),
    KEY idx_replies_user_id (user_id),
    KEY idx_replies_parent_id (parent_id),
    CONSTRAINT fk_replies_post FOREIGN KEY (post_id) REFERENCES posts (id) ON DELETE CASCADE,
    CONSTRAINT fk_replies_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE SET NULL,
    CONSTRAINT fk_replies_parent FOREIGN KEY (parent_id) REFERENCES replies (id) ON DELETE SET NULL
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

-- 点赞表：user_id 结构。
-- 若旧库还是 visitor_id 结构，由 DatabaseMigrator 检测后清掉重建；此处不 DROP，
-- 避免每次启动都清空正常的点赞数据。
CREATE TABLE IF NOT EXISTS post_likes (
    post_id     BIGINT       NOT NULL,
    user_id     BIGINT       NOT NULL,
    created_at  DATETIME     DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (post_id, user_id),
    CONSTRAINT fk_post_likes_post FOREIGN KEY (post_id) REFERENCES posts (id) ON DELETE CASCADE,
    CONSTRAINT fk_post_likes_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS reply_likes (
    reply_id    BIGINT       NOT NULL,
    user_id     BIGINT       NOT NULL,
    created_at  DATETIME     DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (reply_id, user_id),
    CONSTRAINT fk_reply_likes_reply FOREIGN KEY (reply_id) REFERENCES replies (id) ON DELETE CASCADE,
    CONSTRAINT fk_reply_likes_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS attachments (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    post_id       BIGINT       NOT NULL,
    original_name VARCHAR(255),
    stored_name   VARCHAR(255),
    mime_type     VARCHAR(100),
    size          BIGINT       DEFAULT 0,
    url_path      VARCHAR(500),
    created_at    DATETIME     DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_attachments_post_id (post_id),
    CONSTRAINT fk_attachments_post FOREIGN KEY (post_id) REFERENCES posts (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

-- 关注关系：follower 关注 following。同一对用户唯一，删除用户级联清理。
CREATE TABLE IF NOT EXISTS follows (
    follower_id  BIGINT   NOT NULL,
    following_id BIGINT   NOT NULL,
    created_at   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (follower_id, following_id),
    KEY idx_follows_following (following_id),
    CONSTRAINT fk_follows_follower  FOREIGN KEY (follower_id)  REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_follows_following FOREIGN KEY (following_id) REFERENCES users (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

-- 私聊会话：一对用户一个会话。user_low / user_high 固定为 LEAST / GREATEST(userA, userB)，保证同对用户唯一。
CREATE TABLE IF NOT EXISTS dm_conversations (
    id         BIGINT   NOT NULL AUTO_INCREMENT,
    user_low   BIGINT   NOT NULL,
    user_high  BIGINT   NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_dm_conversations_pair (user_low, user_high),
    CONSTRAINT fk_dm_conv_low  FOREIGN KEY (user_low)  REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_dm_conv_high FOREIGN KEY (user_high) REFERENCES users (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

-- 私聊消息：挂在会话下，sender 是发送方，is_read 表示接收方是否已读。
CREATE TABLE IF NOT EXISTS dm_messages (
    id              BIGINT        NOT NULL AUTO_INCREMENT,
    conversation_id BIGINT        NOT NULL,
    sender_id       BIGINT        NOT NULL,
    content         VARCHAR(2000) NOT NULL,
    is_read         TINYINT(1)    NOT NULL DEFAULT 0,
    created_at      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_dm_messages_conv (conversation_id, id),
    KEY idx_dm_messages_sender (sender_id),
    CONSTRAINT fk_dm_messages_conv   FOREIGN KEY (conversation_id) REFERENCES dm_conversations (id) ON DELETE CASCADE,
    CONSTRAINT fk_dm_messages_sender FOREIGN KEY (sender_id) REFERENCES users (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

-- 屏蔽关系：blocker 屏蔽 blocked。屏蔽后双方互不可见对方帖子，私聊显示屏蔽状态。
CREATE TABLE IF NOT EXISTS user_blocks (
    blocker_id  BIGINT   NOT NULL,
    blocked_id  BIGINT   NOT NULL,
    created_at  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (blocker_id, blocked_id),
    KEY idx_user_blocks_blocked (blocked_id),
    CONSTRAINT fk_user_blocks_blocker FOREIGN KEY (blocker_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_user_blocks_blocked FOREIGN KEY (blocked_id) REFERENCES users (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

-- 消息通知：mention（被 @）现在支持；announcement（公告）为未来预留，type 区分、title/content 承载正文。
CREATE TABLE IF NOT EXISTS notifications (
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    user_id    BIGINT       NOT NULL COMMENT '接收者',
    type       VARCHAR(20)  NOT NULL DEFAULT 'mention' COMMENT 'mention/announcement，公告预留',
    actor_id   BIGINT       NULL,
    post_id    BIGINT       NULL,
    reply_id   BIGINT       NULL,
    title      VARCHAR(100) NULL,
    content    VARCHAR(500) NULL,
    is_read    TINYINT(1)   NOT NULL DEFAULT 0,
    created_at DATETIME     DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_notifications_user (user_id, id),
    CONSTRAINT fk_notifications_user  FOREIGN KEY (user_id)  REFERENCES users (id)  ON DELETE CASCADE,
    CONSTRAINT fk_notifications_actor FOREIGN KEY (actor_id) REFERENCES users (id)  ON DELETE SET NULL,
    CONSTRAINT fk_notifications_post  FOREIGN KEY (post_id)  REFERENCES posts (id)  ON DELETE CASCADE,
    CONSTRAINT fk_notifications_reply FOREIGN KEY (reply_id) REFERENCES replies (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;
