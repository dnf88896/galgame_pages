-- 建表脚本：启动时自动执行，CREATE TABLE IF NOT EXISTS 保证幂等。
-- 对已存在的旧库：posts/replies 的 user_id 列、post_likes/reply_likes 的 user_id 结构
-- 由 com.galgame.config.DatabaseMigrator（ApplicationRunner）在启动时条件迁移完成。

CREATE TABLE IF NOT EXISTS users (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    username      VARCHAR(32)  NOT NULL,
    nickname      VARCHAR(32)  NULL COMMENT '用户昵称，初始=账号名，可重复',
    password_hash VARCHAR(100) NOT NULL,
    avatar_url    VARCHAR(500) NULL,
    bio           VARCHAR(200) NULL,
    admin_level   INT          NOT NULL DEFAULT 0 COMMENT '管理员权限等级，0=普通用户，1+ 由管理员密码认证授予',
    hide_favorites TINYINT     NOT NULL DEFAULT 0 COMMENT '是否隐藏收藏夹（0=公开，1=仅自己可见）',
    ban_until      DATETIME     NULL COMMENT '封禁截止时间，NULL=未封禁，2099-12-31 23:59:59=永久封禁；过期自动视为解封',
    moe_points    INT          NOT NULL DEFAULT 0 COMMENT '萌点(积分)：每日签到/发帖/评论奖励，暂只增不消耗',
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
    dislike_count INT        NOT NULL DEFAULT 0 COMMENT '点踩数（与点赞独立，不互斥）',
    cover_image   VARCHAR(500) NULL COMMENT '封面图 URL',
    pinned_until  DATETIME     NULL COMMENT '置顶截止时间，NULL=未置顶，过期自动视为不置顶',
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
    images      TEXT         NULL COMMENT '评论图片URL的JSON数组',
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    like_count  INT          NOT NULL DEFAULT 0,
    dislike_count INT        NOT NULL DEFAULT 0 COMMENT '点踩数（与点赞独立，不互斥）',
    parent_id   BIGINT       NULL,
    parent_author VARCHAR(32) NULL COMMENT '父回复作者名快照：父评论删除后子回复仍能显示「回复 @xx」引用',
    is_pinned   TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '评论置顶标记（发帖人或管理员设置）',
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

-- 点踩表：与点赞独立（同一用户可同时赞和踩），结构同 post_likes。
CREATE TABLE IF NOT EXISTS post_dislikes (
    post_id     BIGINT       NOT NULL,
    user_id     BIGINT       NOT NULL,
    created_at  DATETIME     DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (post_id, user_id),
    CONSTRAINT fk_post_dislikes_post FOREIGN KEY (post_id) REFERENCES posts (id) ON DELETE CASCADE,
    CONSTRAINT fk_post_dislikes_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS reply_likes (
    reply_id    BIGINT       NOT NULL,
    user_id     BIGINT       NOT NULL,
    created_at  DATETIME     DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (reply_id, user_id),
    CONSTRAINT fk_reply_likes_reply FOREIGN KEY (reply_id) REFERENCES replies (id) ON DELETE CASCADE,
    CONSTRAINT fk_reply_likes_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

-- 回复点踩表：与点赞独立，结构同 reply_likes。
CREATE TABLE IF NOT EXISTS reply_dislikes (
    reply_id    BIGINT       NOT NULL,
    user_id     BIGINT       NOT NULL,
    created_at  DATETIME     DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (reply_id, user_id),
    CONSTRAINT fk_reply_dislikes_reply FOREIGN KEY (reply_id) REFERENCES replies (id) ON DELETE CASCADE,
    CONSTRAINT fk_reply_dislikes_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
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
    is_recalled     TINYINT(1)    NOT NULL DEFAULT 0 COMMENT '是否已撤回（内容保留，前端显示"已撤回"）',
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
    kungal_id   BIGINT       NULL COMMENT 'KUNGal 作品 gid（批量导入唯一键，防重复）',
    name        VARCHAR(200) NOT NULL COMMENT 'Galgame 名称',
    description TEXT         NULL COMMENT '简介',
    image       VARCHAR(500) NULL COMMENT '封面图 URL（本地上传 /uploads/galgame_images/ 或外部链接）',
    staff       VARCHAR(500) NULL COMMENT '制作人员 / 会社（存公司名快照用于展示/搜索；有公司关联时=公司名）',
    company_id  BIGINT       NULL COMMENT '关联会社 id（companies 表，无外键约束，仅跳转用；NULL=未关联会社）',
    view_count  INT          NOT NULL DEFAULT 0 COMMENT '总浏览数（详情页访问 +1）',
    release_date DATE        NULL COMMENT '发售日期',
    rating_avg   DECIMAL(4,2) NULL COMMENT '评分平均分（用户评分汇总，管理员不可写）',
    rating_count INT          NOT NULL DEFAULT 0 COMMENT '评分人数（一人一票）',
    links       TEXT         NULL COMMENT '资源链接 JSON 数组文本（[{label,url}]）',
    created_by  BIGINT       NULL,
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    status      VARCHAR(20)  NOT NULL DEFAULT 'approved' COMMENT '状态：approved已上架/pending待审核/rejected已拒绝',
    reject_reason VARCHAR(500) NULL COMMENT '拒绝理由',
    reviewed_at DATETIME     NULL COMMENT '审核时间',
    moe_awarded TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '审核通过萌点奖励是否已发放（每条只奖一次）',
    apply_type  VARCHAR(10)  NOT NULL DEFAULT 'create' COMMENT '申请类型：create创建申请 / update修改申请（影子行）',
    original_id BIGINT       NULL COMMENT '修改申请影子行的原记录 id（apply_type=update 时有值）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_galgames_kungal_id (kungal_id),
    KEY idx_galgames_name (name),
    KEY idx_galgames_original_id (original_id),
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
    galgame_id  BIGINT          NOT NULL,
    user_id     BIGINT          NOT NULL,
    score       DECIMAL(3,1)    NULL COMMENT '该用户评分（0~10，0.5 步进）；详情页已评分回显；历史记录为 NULL 表示当时未存分数',
    created_at  DATETIME        DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (galgame_id, user_id),
    KEY idx_galgame_ratings_user (user_id),
    CONSTRAINT fk_galgame_ratings_galgame FOREIGN KEY (galgame_id) REFERENCES galgames (id) ON DELETE CASCADE,
    CONSTRAINT fk_galgame_ratings_user    FOREIGN KEY (user_id)    REFERENCES users (id)    ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

-- Galgame 详情页评论：仿 replies 表，但无 is_pinned 列（本轮不做评论置顶）。
CREATE TABLE IF NOT EXISTS galgame_replies (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    galgame_id  BIGINT       NOT NULL,
    user_id     BIGINT       NULL,
    author      VARCHAR(32)  NOT NULL DEFAULT '匿名',
    content     TEXT         NOT NULL,
    is_long     TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '1=长评(>300字)，0=短评',
    images      TEXT         NULL COMMENT '评论图片URL的JSON数组',
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    like_count  INT          NOT NULL DEFAULT 0,
    dislike_count INT        NOT NULL DEFAULT 0 COMMENT '点踩数（与点赞独立，不互斥）',
    parent_id   BIGINT       NULL,
    parent_author VARCHAR(32) NULL COMMENT '父评论作者名快照：父评论删除后子评论仍能显示「回复 @xx」引用',
    PRIMARY KEY (id),
    KEY idx_galgame_replies_galgame (galgame_id),
    KEY idx_galgame_replies_user (user_id),
    KEY idx_galgame_replies_parent (parent_id),
    CONSTRAINT fk_galgame_replies_galgame FOREIGN KEY (galgame_id) REFERENCES galgames (id) ON DELETE CASCADE,
    CONSTRAINT fk_galgame_replies_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE SET NULL,
    CONSTRAINT fk_galgame_replies_parent FOREIGN KEY (parent_id) REFERENCES galgame_replies (id) ON DELETE SET NULL
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

-- Galgame 评论点赞表：结构同 reply_likes。
CREATE TABLE IF NOT EXISTS galgame_reply_likes (
    reply_id    BIGINT       NOT NULL,
    user_id     BIGINT       NOT NULL,
    created_at  DATETIME     DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (reply_id, user_id),
    CONSTRAINT fk_galgame_reply_likes_reply FOREIGN KEY (reply_id) REFERENCES galgame_replies (id) ON DELETE CASCADE,
    CONSTRAINT fk_galgame_reply_likes_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

-- Galgame 评论点踩表：与点赞独立，结构同 galgame_reply_likes。
CREATE TABLE IF NOT EXISTS galgame_reply_dislikes (
    reply_id    BIGINT       NOT NULL,
    user_id     BIGINT       NOT NULL,
    created_at  DATETIME     DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (reply_id, user_id),
    CONSTRAINT fk_galgame_reply_dislikes_reply FOREIGN KEY (reply_id) REFERENCES galgame_replies (id) ON DELETE CASCADE,
    CONSTRAINT fk_galgame_reply_dislikes_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

-- 会社（制作公司）库：普通用户提交 → 管理员审核（仿 galgames 提交审核）。
-- 详情页访问浏览数 +1；审核通过给提交者 +10 萌点（moe_awarded 按条只奖一次）。
CREATE TABLE IF NOT EXISTS companies (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    name        VARCHAR(200) NOT NULL COMMENT '会社名称',
    description TEXT         NULL COMMENT '简介',
    website     VARCHAR(500) NULL COMMENT '官网 URL',
    logo_image  VARCHAR(500) NULL COMMENT 'Logo 图 URL（预留，暂未开放上传）',
    view_count  INT          NOT NULL DEFAULT 0 COMMENT '总浏览数（详情页访问 +1）',
    created_by  BIGINT       NULL,
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    status      VARCHAR(20)  NOT NULL DEFAULT 'approved' COMMENT '状态：approved已上架/pending待审核/rejected已拒绝',
    reject_reason VARCHAR(500) NULL COMMENT '拒绝理由',
    reviewed_at DATETIME     NULL COMMENT '审核时间',
    moe_awarded TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '审核通过萌点奖励是否已发放（每条只奖一次）',
    apply_type  VARCHAR(10)  NOT NULL DEFAULT 'create' COMMENT '申请类型：create创建申请 / update修改申请（影子行）',
    original_id BIGINT       NULL COMMENT '修改申请影子行的原记录 id（apply_type=update 时有值）',
    PRIMARY KEY (id),
    KEY idx_companies_name (name),
    KEY idx_companies_original_id (original_id),
    CONSTRAINT fk_companies_user FOREIGN KEY (created_by) REFERENCES users (id) ON DELETE SET NULL
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

-- 举报：用户举报帖子、帖子评论或 Galgame 评论。target_type 区分 post/reply/greply；同一举报人对同一目标只记一条（UNIQUE）。
-- status：0=待处理，1=已删除，2=已忽略；result 为处理结果（用于通知举报人）。
CREATE TABLE IF NOT EXISTS reports (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    reporter_id BIGINT       NOT NULL COMMENT '举报人',
    target_type VARCHAR(10)  NOT NULL DEFAULT 'post' COMMENT 'post=帖子 / reply=评论 / greply=Galgame评论',
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

-- 帖子投票：每帖最多 30 个（kungal 一致），仅楼主可建/编/删，所有登录用户可投。
-- user_id 用 CASCADE：用户删号时其投票一并删除（投票归属随用户）。
CREATE TABLE IF NOT EXISTS polls (
    id                BIGINT       NOT NULL AUTO_INCREMENT,
    title             VARCHAR(100) NOT NULL,
    description       VARCHAR(500) NOT NULL DEFAULT '',
    type              VARCHAR(10)  NOT NULL DEFAULT 'single' COMMENT 'single=单选 / multiple=多选',
    min_choice        INT          NOT NULL DEFAULT 1 COMMENT '多选时至少选择数',
    max_choice        INT          NOT NULL DEFAULT 1 COMMENT '多选时至多选择数',
    deadline          DATETIME     NULL COMMENT '截止时间，NULL=长期开放',
    status            VARCHAR(10)  NOT NULL DEFAULT 'open' COMMENT 'open=进行中 / closed=已关闭',
    result_visibility VARCHAR(20)  NOT NULL DEFAULT 'always' COMMENT 'always=所有人可见 / after_vote=投票后可见 / after_deadline=截止后可见',
    is_anonymous      TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '匿名投票：不显示投票人身份',
    can_change_vote   TINYINT(1)   NOT NULL DEFAULT 1 COMMENT '允许修改投票',
    post_id           BIGINT       NOT NULL,
    user_id           BIGINT       NOT NULL COMMENT '创建人（楼主）',
    created_at        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_polls_post_id (post_id),
    KEY idx_polls_user_id (user_id),
    CONSTRAINT fk_polls_post FOREIGN KEY (post_id) REFERENCES posts (id) ON DELETE CASCADE,
    CONSTRAINT fk_polls_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

-- 投票选项：vote_count 为冗余计数（与 poll_votes 行数保持一致，事务内增减）。
CREATE TABLE IF NOT EXISTS poll_options (
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    text       VARCHAR(100) NOT NULL,
    poll_id    BIGINT       NOT NULL,
    vote_count INT          NOT NULL DEFAULT 0,
    created_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_poll_options_poll (poll_id),
    CONSTRAINT fk_poll_options_poll FOREIGN KEY (poll_id) REFERENCES polls (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

-- 投票记录：唯一键 (poll_id, option_id, user_id) 防同一用户重复投同一选项；删用户/删投票/删选项级联清理。
CREATE TABLE IF NOT EXISTS poll_votes (
    id         BIGINT   NOT NULL AUTO_INCREMENT,
    poll_id    BIGINT   NOT NULL,
    option_id  BIGINT   NOT NULL,
    user_id    BIGINT   NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_poll_option_user (poll_id, option_id, user_id),
    KEY idx_poll_votes_user (user_id, poll_id),
    KEY idx_poll_votes_option (option_id),
    CONSTRAINT fk_poll_votes_poll   FOREIGN KEY (poll_id)   REFERENCES polls (id)         ON DELETE CASCADE,
    CONSTRAINT fk_poll_votes_option FOREIGN KEY (option_id) REFERENCES poll_options (id) ON DELETE CASCADE,
    CONSTRAINT fk_poll_votes_user   FOREIGN KEY (user_id)   REFERENCES users (id)        ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

-- 每日奖励防重：同一用户同一动作类型每天只记一行（签到/发帖/评论）。
-- 唯一键 (user_id, action_type, action_date) + INSERT IGNORE 保证并发安全（数据库兜底，无需先查后写）。
CREATE TABLE IF NOT EXISTS daily_rewards (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    user_id     BIGINT       NOT NULL,
    action_type VARCHAR(20)  NOT NULL COMMENT 'check_in=签到 / post=发帖 / reply=评论',
    action_date DATE         NOT NULL,
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_daily_user_action (user_id, action_type, action_date),
    KEY idx_daily_user_date (user_id, action_date),
    CONSTRAINT fk_daily_rewards_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

-- 制作人员库：普通用户提交 → 管理员审核（仿 companies 提交审核）。
-- 详情页访问浏览数 +1；审核通过给提交者 +10 萌点（moe_awarded 按条只奖一次）。
-- 与角色通过 staff_character 多对多关联。
CREATE TABLE IF NOT EXISTS staffs (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    name        VARCHAR(200) NOT NULL COMMENT '制作人员名称',
    description TEXT         NULL COMMENT '简介',
    image       VARCHAR(500) NULL COMMENT '封面图 URL',
    view_count  INT          NOT NULL DEFAULT 0 COMMENT '总浏览数（详情页访问 +1）',
    created_by  BIGINT       NULL,
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    status      VARCHAR(20)  NOT NULL DEFAULT 'approved' COMMENT '状态：approved已上架/pending待审核/rejected已拒绝',
    reject_reason VARCHAR(500) NULL COMMENT '拒绝理由',
    reviewed_at DATETIME     NULL COMMENT '审核时间',
    moe_awarded TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '审核通过萌点奖励是否已发放（每条只奖一次）',
    apply_type  VARCHAR(10)  NOT NULL DEFAULT 'create' COMMENT '申请类型：create创建申请 / update修改申请（影子行）',
    original_id BIGINT       NULL COMMENT '修改申请影子行的原记录 id（apply_type=update 时有值）',
    PRIMARY KEY (id),
    KEY idx_staffs_name (name),
    KEY idx_staffs_status (status),
    KEY idx_staffs_original_id (original_id),
    CONSTRAINT fk_staffs_user FOREIGN KEY (created_by) REFERENCES users (id) ON DELETE SET NULL
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

-- 角色库：普通用户提交 → 管理员审核（仿 staffs / companies 提交审核）。
-- 与制作人员通过 staff_character 多对多关联。
CREATE TABLE IF NOT EXISTS characters (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    name        VARCHAR(200) NOT NULL COMMENT '角色名称',
    description TEXT         NULL COMMENT '简介',
    image       VARCHAR(500) NULL COMMENT '封面图 URL',
    view_count  INT          NOT NULL DEFAULT 0 COMMENT '总浏览数（详情页访问 +1）',
    created_by  BIGINT       NULL,
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    status      VARCHAR(20)  NOT NULL DEFAULT 'approved' COMMENT '状态：approved已上架/pending待审核/rejected已拒绝',
    reject_reason VARCHAR(500) NULL COMMENT '拒绝理由',
    reviewed_at DATETIME     NULL COMMENT '审核时间',
    moe_awarded TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '审核通过萌点奖励是否已发放（每条只奖一次）',
    apply_type  VARCHAR(10)  NOT NULL DEFAULT 'create' COMMENT '申请类型：create创建申请 / update修改申请（影子行）',
    original_id BIGINT       NULL COMMENT '修改申请影子行的原记录 id（apply_type=update 时有值）',
    PRIMARY KEY (id),
    KEY idx_characters_name (name),
    KEY idx_characters_status (status),
    KEY idx_characters_original_id (original_id),
    CONSTRAINT fk_characters_user FOREIGN KEY (created_by) REFERENCES users (id) ON DELETE SET NULL
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

-- Galgame-制作人员多对多：一作可关联多个制作人员，一个制作人员可参与多作。
CREATE TABLE IF NOT EXISTS galgame_staff (
    galgame_id  BIGINT       NOT NULL,
    staff_id    BIGINT       NOT NULL,
    description VARCHAR(200) NULL COMMENT '制作人员在作品中的职责/备注',
    created_at  DATETIME     DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (galgame_id, staff_id),
    KEY idx_gs_staff (staff_id),
    CONSTRAINT fk_gs_galgame FOREIGN KEY (galgame_id) REFERENCES galgames (id) ON DELETE CASCADE,
    CONSTRAINT fk_gs_staff FOREIGN KEY (staff_id) REFERENCES staffs (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

-- Galgame-角色多对多：一作可关联多个角色，一个角色可出现在多作。
CREATE TABLE IF NOT EXISTS galgame_character (
    galgame_id   BIGINT       NOT NULL,
    character_id BIGINT       NOT NULL,
    description  VARCHAR(200) NULL COMMENT '角色在作品中的定位/备注',
    created_at   DATETIME     DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (galgame_id, character_id),
    KEY idx_gc_character (character_id),
    CONSTRAINT fk_gc_galgame FOREIGN KEY (galgame_id) REFERENCES galgames (id) ON DELETE CASCADE,
    CONSTRAINT fk_gc_character FOREIGN KEY (character_id) REFERENCES characters (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

-- 制作人员-角色多对多：一个制作人员可演绎多个角色，一个角色可被多个制作人员演绎。
CREATE TABLE IF NOT EXISTS staff_character (
    staff_id     BIGINT       NOT NULL,
    character_id BIGINT       NOT NULL,
    created_at   DATETIME     DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (staff_id, character_id),
    KEY idx_sc_character (character_id),
    CONSTRAINT fk_sc_staff FOREIGN KEY (staff_id) REFERENCES staffs (id) ON DELETE CASCADE,
    CONSTRAINT fk_sc_character FOREIGN KEY (character_id) REFERENCES characters (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

-- 标签实体库（Galgame 标签系统）：普通用户提交 → 管理员审核（仿 staffs / companies 提交审核）。
-- name 唯一（中文标签名）；alias 为旧 gg-* section_key 别名（播种/迁移兼容用，不在前端展示）；
-- category 七枚举：type资源类型/language语言/platform平台/content游戏内容/meta作品属性/technical技术细节/sexual成人内容；
-- spoiler_level 剧透级 0无/1轻微/2严重；status 审核状态（approved已上架/pending待审核/rejected已拒绝）；
-- moe_awarded 审核通过萌点奖励是否已发放（每条只奖一次）。
CREATE TABLE IF NOT EXISTS tags (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    name          VARCHAR(50)  NOT NULL COMMENT '标签名称（唯一，中文）',
    alias         VARCHAR(50)  NULL COMMENT '旧 section_key 别名（gg-*），播种迁移/兼容用',
    category      VARCHAR(20)  NOT NULL DEFAULT 'content' COMMENT 'type资源类型/language语言/platform平台/content游戏内容/meta作品属性/technical技术细节/sexual成人内容',
    spoiler_level INT          NOT NULL DEFAULT 0 COMMENT '剧透等级：0无剧透/1轻微剧透/2严重剧透',
    description   VARCHAR(200) NULL COMMENT '标签说明',
    created_by    BIGINT       NULL,
    created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    status        VARCHAR(20)  NOT NULL DEFAULT 'approved' COMMENT 'approved/pending/rejected',
    reject_reason VARCHAR(500) NULL,
    reviewed_at   DATETIME     NULL,
    moe_awarded   TINYINT(1)   NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_tags_name (name),
    KEY idx_tags_category (category),
    KEY idx_tags_status (status),
    CONSTRAINT fk_tags_user FOREIGN KEY (created_by) REFERENCES users (id) ON DELETE SET NULL
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

-- Galgame-标签多对多（替代旧 galgame_tags，旧表保留不删）：一作可挂多标签，一标签可被多作使用。
CREATE TABLE IF NOT EXISTS galgame_tag (
    galgame_id BIGINT   NOT NULL,
    tag_id     BIGINT   NOT NULL,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (galgame_id, tag_id),
    KEY idx_gt_tag (tag_id),
    CONSTRAINT fk_gt_galgame FOREIGN KEY (galgame_id) REFERENCES galgames (id) ON DELETE CASCADE,
    CONSTRAINT fk_gt_tag FOREIGN KEY (tag_id) REFERENCES tags (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

-- Galgame 画廊多图：一作可传多张图（封面图仍存 galgames.image 单图），sort_order 排序（先插先显示）。
CREATE TABLE IF NOT EXISTS galgame_images (
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    galgame_id BIGINT       NOT NULL,
    url        VARCHAR(500) NOT NULL COMMENT '图片相对 URL（/uploads/galgame_images/...）',
    sort_order INT          NOT NULL DEFAULT 0,
    created_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_gi_galgame (galgame_id),
    CONSTRAINT fk_gi_galgame FOREIGN KEY (galgame_id) REFERENCES galgames (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

-- Galgame 相关系列：一作可关联多作（双向跳转），仅关联已上架作品；主键 (galgame_id, related_id) 防重复关联。
CREATE TABLE IF NOT EXISTS galgame_related (
    galgame_id BIGINT   NOT NULL,
    related_id BIGINT   NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (galgame_id, related_id),
    KEY idx_gr_related (related_id),
    CONSTRAINT fk_gr_galgame FOREIGN KEY (galgame_id) REFERENCES galgames (id) ON DELETE CASCADE,
    CONSTRAINT fk_gr_related FOREIGN KEY (related_id) REFERENCES galgames (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

-- 系列：批量导入时持久化 KUNGal 的 series（id 取 KUNGal series id），供跨批关联用。
-- 每次导入后按系列全库重算 galgame_related，新批次作品会自动和库内已有同系列作品互相关联。
CREATE TABLE IF NOT EXISTS series (
    id         BIGINT       NOT NULL PRIMARY KEY COMMENT 'KUNGal series id',
    name       VARCHAR(200) NOT NULL,
    created_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

-- Galgame 所属系列（多对多：一作可属多系列）：脚本写入，全库关联重算的数据源。
-- 注意约束名前缀 fk_gser_*（fk_gs_* 已被 galgame_staff 占用，MySQL 8.0 库内约束名唯一）。
CREATE TABLE IF NOT EXISTS galgame_series (
    galgame_id BIGINT   NOT NULL,
    series_id  BIGINT   NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (galgame_id, series_id),
    CONSTRAINT fk_gser_galgame FOREIGN KEY (galgame_id) REFERENCES galgames (id) ON DELETE CASCADE,
    CONSTRAINT fk_gser_series  FOREIGN KEY (series_id) REFERENCES series (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

-- 条目贡献者：记录谁贡献了 galgame / company / staff / character 条目
-- （创建者 / 修改申请提交者 / 管理员原地直改都被记为贡献者）。
-- 复合主键 (entry_type, entry_id, user_id) 天然防同一人重复贡献；用户删号由 FK ON DELETE CASCADE 级联清理；
-- 删除条目时由 Controller 显式清理贡献者记录（与 galgame_images 删除行为一致，不依赖级联）。
CREATE TABLE IF NOT EXISTS entity_contributors (
    entry_type VARCHAR(20) NOT NULL COMMENT '条目类型：galgame/company/staff/character',
    entry_id   BIGINT      NOT NULL COMMENT '条目 id（对应各实体表主键）',
    user_id    BIGINT      NOT NULL COMMENT '贡献者用户 id',
    created_at DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '贡献时间',
    PRIMARY KEY (entry_type, entry_id, user_id),
    KEY idx_ec_entry (entry_type, entry_id),
    CONSTRAINT fk_ec_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;
