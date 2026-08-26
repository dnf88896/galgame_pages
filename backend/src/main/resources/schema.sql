-- 建表脚本：启动时自动执行，CREATE TABLE IF NOT EXISTS 保证幂等。
-- 对已存在的旧库：posts/replies 的 user_id 列、post_likes/reply_likes 的 user_id 结构
-- 由 com.galgame.config.DatabaseMigrator（ApplicationRunner）在启动时条件迁移完成。

CREATE TABLE IF NOT EXISTS users (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    username      VARCHAR(32)  NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    avatar_url    VARCHAR(500) NULL,
    bio           VARCHAR(200) NULL,
    admin_level   INT          NOT NULL DEFAULT 0 COMMENT '管理员权限等级，0=普通用户，1+ 由管理员密码认证授予',
    hide_favorites TINYINT     NOT NULL DEFAULT 0 COMMENT '是否隐藏收藏夹（0=公开，1=仅自己可见）',
    ban_until      DATETIME     NULL COMMENT '封禁截止时间，NULL=未封禁，2099-12-31 23:59:59=永久封禁；过期自动视为解封',
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
    media      TEXT         NULL COMMENT '媒体附件 JSON（[{url,mime,name}]，公告广播用）',
    is_read    TINYINT(1)   NOT NULL DEFAULT 0,
    created_at DATETIME     DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_notifications_user (user_id, id),
    CONSTRAINT fk_notifications_user  FOREIGN KEY (user_id)  REFERENCES users (id)  ON DELETE CASCADE,
    CONSTRAINT fk_notifications_actor FOREIGN KEY (actor_id) REFERENCES users (id)  ON DELETE SET NULL,
    CONSTRAINT fk_notifications_post  FOREIGN KEY (post_id)  REFERENCES posts (id)  ON DELETE CASCADE,
    CONSTRAINT fk_notifications_reply FOREIGN KEY (reply_id) REFERENCES replies (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

-- Galgame 作品库：管理员「添加galgame」维护。字段为 TEXT/较长 VARCHAR 留扩展空间，
-- 后续需要追加信息（评分/发售日期/厂商官网等）时直接加列即可；links 存 JSON 数组文本（[{label,url}]）。
CREATE TABLE IF NOT EXISTS galgames (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    name        VARCHAR(200) NOT NULL COMMENT 'Galgame 名称',
    description TEXT         NULL COMMENT '简介',
    image       VARCHAR(500) NULL COMMENT '封面图 URL（本地上传 /uploads/galgame_images/ 或外部链接）',
    staff       VARCHAR(500) NULL COMMENT '制作人员 / 会社',
    view_count  INT          NOT NULL DEFAULT 0 COMMENT '总浏览数（详情页访问 +1）',
    release_date DATE        NULL COMMENT '发售日期',
    rating_avg   DECIMAL(4,2) NULL COMMENT '评分平均分（用户评分汇总，管理员不可写）',
    rating_count INT          NOT NULL DEFAULT 0 COMMENT '评分人数（一人一票）',
    links       TEXT         NULL COMMENT '资源链接 JSON 数组文本（[{label,url}]）',
    created_by  BIGINT       NULL,
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_galgames_name (name),
    CONSTRAINT fk_galgames_user FOREIGN KEY (created_by) REFERENCES users (id) ON DELETE SET NULL
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

-- Galgame-标签多对多：标签复用 galgame-resource 大类下的 gg-* section_key。
CREATE TABLE IF NOT EXISTS galgame_tags (
    galgame_id  BIGINT      NOT NULL,
    section_key VARCHAR(32) NOT NULL,
    PRIMARY KEY (galgame_id, section_key),
    KEY idx_galgame_tags_section (section_key),
    CONSTRAINT fk_galgame_tags_galgame FOREIGN KEY (galgame_id) REFERENCES galgames (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

-- Galgame 评分防重：只记录「谁评过」（不含分数），防止同一用户重复评分刷分。
-- 平均分 / 人数增量维护在 galgames.rating_avg / rating_count（新评分 = (旧平均×人数 + 新分) / (人数+1)）。
CREATE TABLE IF NOT EXISTS galgame_ratings (
    galgame_id  BIGINT      NOT NULL,
    user_id     BIGINT      NOT NULL,
    created_at  DATETIME    DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (galgame_id, user_id),
    KEY idx_galgame_ratings_user (user_id),
    CONSTRAINT fk_galgame_ratings_galgame FOREIGN KEY (galgame_id) REFERENCES galgames (id) ON DELETE CASCADE,
    CONSTRAINT fk_galgame_ratings_user    FOREIGN KEY (user_id)    REFERENCES users (id)    ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

-- 收藏夹：用户收藏帖子（user_id 收藏 post_id），公开可见，可选隐藏（users.hide_favorites）。
CREATE TABLE IF NOT EXISTS post_favorites (
    post_id     BIGINT       NOT NULL,
    user_id     BIGINT       NOT NULL,
    created_at  DATETIME     DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (post_id, user_id),
    KEY idx_post_favorites_user (user_id),
    CONSTRAINT fk_post_favorites_post FOREIGN KEY (post_id) REFERENCES posts (id) ON DELETE CASCADE,
    CONSTRAINT fk_post_favorites_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

-- 举报：用户举报帖子或评论。target_type 区分 post/reply；同一举报人对同一目标只记一条（UNIQUE）。
-- status：0=待处理，1=已删除，2=已忽略；result 为处理结果（用于通知举报人）。
CREATE TABLE IF NOT EXISTS reports (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    reporter_id BIGINT       NOT NULL COMMENT '举报人',
    target_type VARCHAR(10)  NOT NULL DEFAULT 'post' COMMENT 'post=帖子 / reply=评论',
    target_id   BIGINT       NOT NULL COMMENT '被举报目标 id',
    reason      VARCHAR(200) NULL COMMENT '举报原因（可选）',
    status      TINYINT      NOT NULL DEFAULT 0 COMMENT '0=待处理，1=已删除，2=已忽略',
    handled_at  DATETIME     NULL COMMENT '处理时间',
    result      VARCHAR(50)  NULL COMMENT '处理结果（供通知举报人）',
    created_at  DATETIME     DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_reports_reporter_target (reporter_id, target_type, target_id),
    KEY idx_reports_target (target_type, target_id),
    KEY idx_reports_status (status, id),
    CONSTRAINT fk_reports_user FOREIGN KEY (reporter_id) REFERENCES users (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;
