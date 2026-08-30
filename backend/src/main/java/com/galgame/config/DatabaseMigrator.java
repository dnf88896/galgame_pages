package com.galgame.config;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 启动时的数据库增量迁移（schema.sql 的 CREATE TABLE IF NOT EXISTS 无法对已有表加列）。
 * <ul>
 *   <li>为旧库的 posts / replies 补加 user_id 列与 FK（老数据该列为 NULL，作者字符串照常显示）；</li>
 *   <li>多标签：确保 post_tags 关联表存在，把旧 posts.section 单值数据迁入 post_tags，然后删除 posts.section 列；</li>
 *   <li>点赞表仍是旧 visitor_id 结构时，清掉重建为 (x_id, user_id) 结构（清空旧的访客点赞数据）；</li>
 *   <li>重算 posts.like_count / replies.like_count = 对应 like 表的实际行数。</li>
 * </ul>
 * 幂等：所有操作都先查 information_schema，已存在 / 已是新结构则跳过。
 */
@Component
public class DatabaseMigrator implements ApplicationRunner {

    private static final String POST_LIKES_DDL =
            "CREATE TABLE post_likes ("
                    + "post_id BIGINT NOT NULL, user_id BIGINT NOT NULL, "
                    + "created_at DATETIME DEFAULT CURRENT_TIMESTAMP, "
                    + "PRIMARY KEY (post_id, user_id), "
                    + "CONSTRAINT fk_post_likes_post FOREIGN KEY (post_id) REFERENCES posts (id) ON DELETE CASCADE, "
                    + "CONSTRAINT fk_post_likes_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci";

    private static final String REPLY_LIKES_DDL =
            "CREATE TABLE reply_likes ("
                    + "reply_id BIGINT NOT NULL, user_id BIGINT NOT NULL, "
                    + "created_at DATETIME DEFAULT CURRENT_TIMESTAMP, "
                    + "PRIMARY KEY (reply_id, user_id), "
                    + "CONSTRAINT fk_reply_likes_reply FOREIGN KEY (reply_id) REFERENCES replies (id) ON DELETE CASCADE, "
                    + "CONSTRAINT fk_reply_likes_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci";

    private static final String POST_DISLIKES_DDL =
            "CREATE TABLE post_dislikes ("
                    + "post_id BIGINT NOT NULL, user_id BIGINT NOT NULL, "
                    + "created_at DATETIME DEFAULT CURRENT_TIMESTAMP, "
                    + "PRIMARY KEY (post_id, user_id), "
                    + "CONSTRAINT fk_post_dislikes_post FOREIGN KEY (post_id) REFERENCES posts (id) ON DELETE CASCADE, "
                    + "CONSTRAINT fk_post_dislikes_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci";

    private static final String REPLY_DISLIKES_DDL =
            "CREATE TABLE reply_dislikes ("
                    + "reply_id BIGINT NOT NULL, user_id BIGINT NOT NULL, "
                    + "created_at DATETIME DEFAULT CURRENT_TIMESTAMP, "
                    + "PRIMARY KEY (reply_id, user_id), "
                    + "CONSTRAINT fk_reply_dislikes_reply FOREIGN KEY (reply_id) REFERENCES replies (id) ON DELETE CASCADE, "
                    + "CONSTRAINT fk_reply_dislikes_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci";

    private static final String POST_FAVORITES_DDL =
            "CREATE TABLE post_favorites ("
                    + "post_id BIGINT NOT NULL, user_id BIGINT NOT NULL, "
                    + "created_at DATETIME DEFAULT CURRENT_TIMESTAMP, "
                    + "PRIMARY KEY (post_id, user_id), "
                    + "KEY idx_post_favorites_user (user_id), "
                    + "CONSTRAINT fk_post_favorites_post FOREIGN KEY (post_id) REFERENCES posts (id) ON DELETE CASCADE, "
                    + "CONSTRAINT fk_post_favorites_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci";

    private static final String GALGAME_RATINGS_DDL =
            "CREATE TABLE galgame_ratings ("
                    + "galgame_id BIGINT NOT NULL, user_id BIGINT NOT NULL, "
                    + "score DECIMAL(3,1) NULL, "
                    + "created_at DATETIME DEFAULT CURRENT_TIMESTAMP, "
                    + "PRIMARY KEY (galgame_id, user_id), "
                    + "KEY idx_galgame_ratings_user (user_id), "
                    + "CONSTRAINT fk_galgame_ratings_galgame FOREIGN KEY (galgame_id) REFERENCES galgames (id) ON DELETE CASCADE, "
                    + "CONSTRAINT fk_galgame_ratings_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci";

    private static final String REPORTS_DDL =
            "CREATE TABLE reports ("
                    + "id BIGINT NOT NULL AUTO_INCREMENT, "
                    + "reporter_id BIGINT NOT NULL, "
                    + "target_type VARCHAR(10) NOT NULL DEFAULT 'post', "
                    + "target_id BIGINT NOT NULL, "
                    + "reason VARCHAR(200) NULL, "
                    + "status TINYINT NOT NULL DEFAULT 0, "
                    + "handled_at DATETIME NULL, "
                    + "result VARCHAR(50) NULL, "
                    + "created_at DATETIME DEFAULT CURRENT_TIMESTAMP, "
                    + "PRIMARY KEY (id), "
                    + "UNIQUE KEY uk_reports_reporter_target (reporter_id, target_type, target_id), "
                    + "KEY idx_reports_target (target_type, target_id), "
                    + "KEY idx_reports_status (status, id), "
                    + "CONSTRAINT fk_reports_user FOREIGN KEY (reporter_id) REFERENCES users (id) ON DELETE CASCADE"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci";

    private static final String POLLS_DDL =
            "CREATE TABLE polls ("
                    + "id BIGINT NOT NULL AUTO_INCREMENT, "
                    + "title VARCHAR(100) NOT NULL, "
                    + "description VARCHAR(500) NOT NULL DEFAULT '', "
                    + "type VARCHAR(10) NOT NULL DEFAULT 'single', "
                    + "min_choice INT NOT NULL DEFAULT 1, "
                    + "max_choice INT NOT NULL DEFAULT 1, "
                    + "deadline DATETIME NULL, "
                    + "status VARCHAR(10) NOT NULL DEFAULT 'open', "
                    + "result_visibility VARCHAR(20) NOT NULL DEFAULT 'always', "
                    + "is_anonymous TINYINT(1) NOT NULL DEFAULT 0, "
                    + "can_change_vote TINYINT(1) NOT NULL DEFAULT 1, "
                    + "post_id BIGINT NOT NULL, "
                    + "user_id BIGINT NOT NULL, "
                    + "created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, "
                    + "updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP, "
                    + "PRIMARY KEY (id), "
                    + "KEY idx_polls_post_id (post_id), "
                    + "KEY idx_polls_user_id (user_id), "
                    + "CONSTRAINT fk_polls_post FOREIGN KEY (post_id) REFERENCES posts (id) ON DELETE CASCADE, "
                    + "CONSTRAINT fk_polls_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci";

    private static final String POLL_OPTIONS_DDL =
            "CREATE TABLE poll_options ("
                    + "id BIGINT NOT NULL AUTO_INCREMENT, "
                    + "text VARCHAR(100) NOT NULL, "
                    + "poll_id BIGINT NOT NULL, "
                    + "vote_count INT NOT NULL DEFAULT 0, "
                    + "created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, "
                    + "PRIMARY KEY (id), "
                    + "KEY idx_poll_options_poll (poll_id), "
                    + "CONSTRAINT fk_poll_options_poll FOREIGN KEY (poll_id) REFERENCES polls (id) ON DELETE CASCADE"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci";

    private static final String POLL_VOTES_DDL =
            "CREATE TABLE poll_votes ("
                    + "id BIGINT NOT NULL AUTO_INCREMENT, "
                    + "poll_id BIGINT NOT NULL, "
                    + "option_id BIGINT NOT NULL, "
                    + "user_id BIGINT NOT NULL, "
                    + "created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, "
                    + "PRIMARY KEY (id), "
                    + "UNIQUE KEY uk_poll_option_user (poll_id, option_id, user_id), "
                    + "KEY idx_poll_votes_user (user_id, poll_id), "
                    + "KEY idx_poll_votes_option (option_id), "
                    + "CONSTRAINT fk_poll_votes_poll FOREIGN KEY (poll_id) REFERENCES polls (id) ON DELETE CASCADE, "
                    + "CONSTRAINT fk_poll_votes_option FOREIGN KEY (option_id) REFERENCES poll_options (id) ON DELETE CASCADE, "
                    + "CONSTRAINT fk_poll_votes_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci";

    private static final String GALGAME_REPLIES_DDL =
            "CREATE TABLE galgame_replies ("
                    + "id BIGINT NOT NULL AUTO_INCREMENT, "
                    + "galgame_id BIGINT NOT NULL, "
                    + "user_id BIGINT NULL, "
                    + "author VARCHAR(32) NOT NULL DEFAULT '匿名', "
                    + "content TEXT NOT NULL, "
                    + "created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, "
                    + "like_count INT NOT NULL DEFAULT 0, "
                    + "dislike_count INT NOT NULL DEFAULT 0, "
                    + "parent_id BIGINT NULL, "
                    + "parent_author VARCHAR(32) NULL, "
                    + "PRIMARY KEY (id), "
                    + "KEY idx_galgame_replies_galgame (galgame_id), "
                    + "KEY idx_galgame_replies_user (user_id), "
                    + "KEY idx_galgame_replies_parent (parent_id), "
                    + "CONSTRAINT fk_galgame_replies_galgame FOREIGN KEY (galgame_id) REFERENCES galgames (id) ON DELETE CASCADE, "
                    + "CONSTRAINT fk_galgame_replies_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE SET NULL, "
                    + "CONSTRAINT fk_galgame_replies_parent FOREIGN KEY (parent_id) REFERENCES galgame_replies (id) ON DELETE SET NULL"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci";

    private static final String GALGAME_REPLY_LIKES_DDL =
            "CREATE TABLE galgame_reply_likes ("
                    + "reply_id BIGINT NOT NULL, user_id BIGINT NOT NULL, "
                    + "created_at DATETIME DEFAULT CURRENT_TIMESTAMP, "
                    + "PRIMARY KEY (reply_id, user_id), "
                    + "CONSTRAINT fk_galgame_reply_likes_reply FOREIGN KEY (reply_id) REFERENCES galgame_replies (id) ON DELETE CASCADE, "
                    + "CONSTRAINT fk_galgame_reply_likes_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci";

    private static final String GALGAME_REPLY_DISLIKES_DDL =
            "CREATE TABLE galgame_reply_dislikes ("
                    + "reply_id BIGINT NOT NULL, user_id BIGINT NOT NULL, "
                    + "created_at DATETIME DEFAULT CURRENT_TIMESTAMP, "
                    + "PRIMARY KEY (reply_id, user_id), "
                    + "CONSTRAINT fk_galgame_reply_dislikes_reply FOREIGN KEY (reply_id) REFERENCES galgame_replies (id) ON DELETE CASCADE, "
                    + "CONSTRAINT fk_galgame_reply_dislikes_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci";

    private static final String DAILY_REWARDS_DDL =
            "CREATE TABLE daily_rewards ("
                    + "id BIGINT NOT NULL AUTO_INCREMENT, "
                    + "user_id BIGINT NOT NULL, "
                    + "action_type VARCHAR(20) NOT NULL, "
                    + "action_date DATE NOT NULL, "
                    + "created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, "
                    + "PRIMARY KEY (id), "
                    + "UNIQUE KEY uk_daily_user_action (user_id, action_type, action_date), "
                    + "KEY idx_daily_user_date (user_id, action_date), "
                    + "CONSTRAINT fk_daily_rewards_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci";

    private static final String STAFFS_DDL =
            "CREATE TABLE staffs ("
                    + "id BIGINT NOT NULL AUTO_INCREMENT, "
                    + "name VARCHAR(200) NOT NULL, "
                    + "description TEXT NULL, "
                    + "image VARCHAR(500) NULL, "
                    + "view_count INT NOT NULL DEFAULT 0, "
                    + "created_by BIGINT NULL, "
                    + "created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, "
                    + "updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP, "
                    + "status VARCHAR(20) NOT NULL DEFAULT 'approved', "
                    + "reject_reason VARCHAR(500) NULL, "
                    + "reviewed_at DATETIME NULL, "
                    + "moe_awarded TINYINT(1) NOT NULL DEFAULT 0, "
                    + "apply_type VARCHAR(10) NOT NULL DEFAULT 'create', "
                    + "original_id BIGINT NULL, "
                    + "PRIMARY KEY (id), "
                    + "KEY idx_staffs_name (name), "
                    + "KEY idx_staffs_status (status), "
                    + "KEY idx_staffs_original_id (original_id), "
                    + "CONSTRAINT fk_staffs_user FOREIGN KEY (created_by) REFERENCES users (id) ON DELETE SET NULL"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci";

    private static final String CHARACTERS_DDL =
            "CREATE TABLE characters ("
                    + "id BIGINT NOT NULL AUTO_INCREMENT, "
                    + "name VARCHAR(200) NOT NULL, "
                    + "description TEXT NULL, "
                    + "image VARCHAR(500) NULL, "
                    + "view_count INT NOT NULL DEFAULT 0, "
                    + "created_by BIGINT NULL, "
                    + "created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, "
                    + "updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP, "
                    + "status VARCHAR(20) NOT NULL DEFAULT 'approved', "
                    + "reject_reason VARCHAR(500) NULL, "
                    + "reviewed_at DATETIME NULL, "
                    + "moe_awarded TINYINT(1) NOT NULL DEFAULT 0, "
                    + "apply_type VARCHAR(10) NOT NULL DEFAULT 'create', "
                    + "original_id BIGINT NULL, "
                    + "PRIMARY KEY (id), "
                    + "KEY idx_characters_name (name), "
                    + "KEY idx_characters_status (status), "
                    + "KEY idx_characters_original_id (original_id), "
                    + "CONSTRAINT fk_characters_user FOREIGN KEY (created_by) REFERENCES users (id) ON DELETE SET NULL"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci";

    private static final String GALGAME_STAFF_DDL =
            "CREATE TABLE galgame_staff ("
                    + "galgame_id BIGINT NOT NULL, staff_id BIGINT NOT NULL, "
                    + "created_at DATETIME DEFAULT CURRENT_TIMESTAMP, "
                    + "PRIMARY KEY (galgame_id, staff_id), "
                    + "KEY idx_gs_staff (staff_id), "
                    + "CONSTRAINT fk_gs_galgame FOREIGN KEY (galgame_id) REFERENCES galgames (id) ON DELETE CASCADE, "
                    + "CONSTRAINT fk_gs_staff FOREIGN KEY (staff_id) REFERENCES staffs (id) ON DELETE CASCADE"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci";

    private static final String GALGAME_CHARACTER_DDL =
            "CREATE TABLE galgame_character ("
                    + "galgame_id BIGINT NOT NULL, character_id BIGINT NOT NULL, "
                    + "created_at DATETIME DEFAULT CURRENT_TIMESTAMP, "
                    + "PRIMARY KEY (galgame_id, character_id), "
                    + "KEY idx_gc_character (character_id), "
                    + "CONSTRAINT fk_gc_galgame FOREIGN KEY (galgame_id) REFERENCES galgames (id) ON DELETE CASCADE, "
                    + "CONSTRAINT fk_gc_character FOREIGN KEY (character_id) REFERENCES characters (id) ON DELETE CASCADE"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci";

    private static final String STAFF_CHARACTER_DDL =
            "CREATE TABLE staff_character ("
                    + "staff_id BIGINT NOT NULL, character_id BIGINT NOT NULL, "
                    + "created_at DATETIME DEFAULT CURRENT_TIMESTAMP, "
                    + "PRIMARY KEY (staff_id, character_id), "
                    + "KEY idx_sc_character (character_id), "
                    + "CONSTRAINT fk_sc_staff FOREIGN KEY (staff_id) REFERENCES staffs (id) ON DELETE CASCADE, "
                    + "CONSTRAINT fk_sc_character FOREIGN KEY (character_id) REFERENCES characters (id) ON DELETE CASCADE"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci";

    private static final String TAGS_DDL =
            "CREATE TABLE tags ("
                    + "id BIGINT NOT NULL AUTO_INCREMENT, "
                    + "name VARCHAR(50) NOT NULL COMMENT '标签名称（唯一，中文）', "
                    + "alias VARCHAR(50) NULL COMMENT '旧 section_key 别名（gg-*），播种迁移/兼容用', "
                    + "category VARCHAR(20) NOT NULL DEFAULT 'content' COMMENT 'type资源类型/language语言/platform平台/content游戏内容/meta作品属性/technical技术细节/sexual成人内容', "
                    + "spoiler_level INT NOT NULL DEFAULT 0 COMMENT '剧透等级：0无剧透/1轻微剧透/2严重剧透', "
                    + "description VARCHAR(200) NULL COMMENT '标签说明', "
                    + "created_by BIGINT NULL, "
                    + "created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, "
                    + "updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP, "
                    + "status VARCHAR(20) NOT NULL DEFAULT 'approved' COMMENT 'approved/pending/rejected', "
                    + "reject_reason VARCHAR(500) NULL, "
                    + "reviewed_at DATETIME NULL, "
                    + "moe_awarded TINYINT(1) NOT NULL DEFAULT 0, "
                    + "PRIMARY KEY (id), "
                    + "UNIQUE KEY uk_tags_name (name), "
                    + "KEY idx_tags_category (category), "
                    + "KEY idx_tags_status (status), "
                    + "CONSTRAINT fk_tags_user FOREIGN KEY (created_by) REFERENCES users (id) ON DELETE SET NULL"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci";

    private static final String GALGAME_TAG_DDL =
            "CREATE TABLE galgame_tag ("
                    + "galgame_id BIGINT NOT NULL, tag_id BIGINT NOT NULL, "
                    + "created_at DATETIME DEFAULT CURRENT_TIMESTAMP, "
                    + "PRIMARY KEY (galgame_id, tag_id), "
                    + "KEY idx_gt_tag (tag_id), "
                    + "CONSTRAINT fk_gt_galgame FOREIGN KEY (galgame_id) REFERENCES galgames (id) ON DELETE CASCADE, "
                    + "CONSTRAINT fk_gt_tag FOREIGN KEY (tag_id) REFERENCES tags (id) ON DELETE CASCADE"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci";

    private static final String GALGAME_IMAGES_DDL =
            "CREATE TABLE galgame_images ("
                    + "id BIGINT NOT NULL AUTO_INCREMENT, "
                    + "galgame_id BIGINT NOT NULL, "
                    + "url VARCHAR(500) NOT NULL, "
                    + "sort_order INT NOT NULL DEFAULT 0, "
                    + "created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, "
                    + "PRIMARY KEY (id), "
                    + "KEY idx_gi_galgame (galgame_id), "
                    + "CONSTRAINT fk_gi_galgame FOREIGN KEY (galgame_id) REFERENCES galgames (id) ON DELETE CASCADE"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci";

    private static final String GALGAME_RELATED_DDL =
            "CREATE TABLE galgame_related ("
                    + "galgame_id BIGINT NOT NULL, related_id BIGINT NOT NULL, "
                    + "created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, "
                    + "PRIMARY KEY (galgame_id, related_id), "
                    + "KEY idx_gr_related (related_id), "
                    + "CONSTRAINT fk_gr_galgame FOREIGN KEY (galgame_id) REFERENCES galgames (id) ON DELETE CASCADE, "
                    + "CONSTRAINT fk_gr_related FOREIGN KEY (related_id) REFERENCES galgames (id) ON DELETE CASCADE"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci";

    private static final String ENTITY_CONTRIBUTORS_DDL =
            "CREATE TABLE entity_contributors ("
                    + "entry_type VARCHAR(20) NOT NULL COMMENT '条目类型：galgame/company/staff/character', "
                    + "entry_id BIGINT NOT NULL COMMENT '条目 id（对应各实体表主键）', "
                    + "user_id BIGINT NOT NULL COMMENT '贡献者用户 id', "
                    + "created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '贡献时间', "
                    + "PRIMARY KEY (entry_type, entry_id, user_id), "
                    + "KEY idx_ec_entry (entry_type, entry_id), "
                    + "CONSTRAINT fk_ec_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci";

    private final JdbcTemplate jdbcTemplate;

    public DatabaseMigrator(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        ensureColumn("posts", "user_id", "BIGINT NULL");
        ensureColumn("replies", "user_id", "BIGINT NULL");
        // 管理员权限等级：旧库 users 表补 admin_level 列（默认 0 = 普通用户）
        ensureColumn("users", "admin_level", "INT NOT NULL DEFAULT 0");
        // 用户昵称：旧库 users 表补 nickname 列（可重复，初始=账号名），并回填存量数据
        ensureColumn("users", "nickname", "VARCHAR(32) NULL COMMENT '用户昵称，初始=账号名，可重复'");
        backfillNickname();
        ensurePostTagsTable();
        migratePostSectionsToTags();
        dropColumnIfExists("posts", "section");
        ensureFk("posts", "fk_posts_user", "FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE SET NULL");
        ensureFk("replies", "fk_replies_user", "FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE SET NULL");
        // 嵌套回复：旧库补 parent_id 列 + 索引 + 自引用 FK（删父回复时子回复保留、引用置 NULL）
        ensureColumn("replies", "parent_id", "BIGINT NULL");
        ensureIndex("replies", "idx_replies_parent_id", "parent_id");
        ensureFk("replies", "fk_replies_parent", "FOREIGN KEY (parent_id) REFERENCES replies (id) ON DELETE SET NULL");
        // 嵌套回复父作者快照：父评论被删后子回复仍能显示「回复 @xx」引用（不是孤儿化）
        ensureColumn("replies", "parent_author", "VARCHAR(32) NULL");
        backfillParentAuthor();

        ensureLikeTable("post_likes", POST_LIKES_DDL);
        ensureLikeTable("reply_likes", REPLY_LIKES_DDL);
        // 点踩：与点赞独立的 dislike 表 + 计数列
        ensureColumn("posts", "dislike_count", "INT NOT NULL DEFAULT 0");
        ensureColumn("replies", "dislike_count", "INT NOT NULL DEFAULT 0");
        ensureLikeTable("post_dislikes", POST_DISLIKES_DDL);
        ensureLikeTable("reply_dislikes", REPLY_DISLIKES_DDL);
        // 帖子封面图
        ensureColumn("posts", "cover_image", "VARCHAR(500) NULL");
        // 帖子置顶：管理员设置截止时间（NULL=未置顶，过期自动视为不置顶）
        ensureColumn("posts", "pinned_until", "DATETIME NULL");
        // 评论置顶：发帖人/管理员可置顶（无时间限制）
        ensureColumn("replies", "is_pinned", "TINYINT(1) NOT NULL DEFAULT 0");
        // 评论图片：replies / galgame_replies 存图片 URL 的 JSON 数组（独立附件方案，非 content 内嵌）
        ensureColumn("replies", "images", "TEXT NULL COMMENT '评论图片URL的JSON数组'");
        ensureColumn("galgame_replies", "images", "TEXT NULL COMMENT '评论图片URL的JSON数组'");
        ensureColumn("galgame_replies", "is_long", "TINYINT(1) NOT NULL DEFAULT 0");
        // 私信撤回
        ensureColumn("dm_messages", "is_recalled", "TINYINT(1) NOT NULL DEFAULT 0");
        // 收藏夹：旧库 users 表补 hide_favorites 列（默认 0 = 公开），并确保 post_favorites 表存在
        ensureColumn("users", "hide_favorites", "TINYINT NOT NULL DEFAULT 0");
        ensureLikeTable("post_favorites", POST_FAVORITES_DDL);
        // 封禁：users 表补 ban_until 列（NULL=未封禁，2099-12-31=永久封禁；过期自动视为解封）
        ensureColumn("users", "ban_until", "DATETIME NULL");
        // 公告媒体：通知表补 media 列（JSON 附件列表，公告广播用）
        ensureColumn("notifications", "media", "TEXT NULL");
        // 举报：确保 reports 表存在（schema.sql 已建，这里兜底旧库），并补处理状态列
        ensureLikeTable("reports", REPORTS_DDL);
        ensureColumn("reports", "status", "TINYINT NOT NULL DEFAULT 0");
        ensureColumn("reports", "handled_at", "DATETIME NULL");
        ensureColumn("reports", "result", "VARCHAR(50) NULL");
        ensureColumn("galgames", "view_count", "INT NOT NULL DEFAULT 0");
        ensureColumn("galgames", "release_date", "DATE NULL");
        migrateGalgameRating();
        // Galgame 提交审核：旧库 galgames 补 status（存量默认 approved=已上架）/ reject_reason / reviewed_at 列
        ensureColumn("galgames", "status", "VARCHAR(20) NOT NULL DEFAULT 'approved'");
        ensureColumn("galgames", "reject_reason", "VARCHAR(500) NULL");
        ensureColumn("galgames", "reviewed_at", "DATETIME NULL");
        // 审核通过萌点奖励标记：每条 galgame 只发一次（防反复通过/拒绝刷萌点）
        ensureColumn("galgames", "moe_awarded", "TINYINT(1) NOT NULL DEFAULT 0");
        // 关联会社：galgames 加 company_id 列（指向 companies 表，无外键约束，仅跳转用）
        ensureColumn("galgames", "company_id", "BIGINT NULL");
        // KUNGal 批量导入唯一键：galgames 加 kungal_id 列 + 唯一索引（import_kungal.py 按 gid 防重复，
        // 老数据无 gid 会在下次导入时按 name 兜底回填）
        ensureColumn("galgames", "kungal_id", "BIGINT NULL COMMENT 'KUNGal 作品 gid（批量导入唯一键，防重复）'");
        ensureUniqueIndex("galgames", "uk_galgames_kungal_id", "kungal_id");
        // 「修改申请」影子行：四实体表补 apply_type（create/update）与 original_id（修改申请指向原记录）
        // 用户在已上架（approved）记录上提交修改时，复制原记录为新行（apply_type='update'、original_id=原id、status='pending'）
        ensureColumn("galgames", "apply_type", "VARCHAR(10) NOT NULL DEFAULT 'create' COMMENT '申请类型：create创建申请 / update修改申请（影子行）'");
        ensureColumn("galgames", "original_id", "BIGINT NULL COMMENT '修改申请影子行的原记录 id（apply_type=update 时有值）'");
        ensureIndex("galgames", "idx_galgames_original_id", "original_id");
        ensureColumn("companies", "apply_type", "VARCHAR(10) NOT NULL DEFAULT 'create' COMMENT '申请类型：create创建申请 / update修改申请（影子行）'");
        ensureColumn("companies", "original_id", "BIGINT NULL COMMENT '修改申请影子行的原记录 id（apply_type=update 时有值）'");
        ensureIndex("companies", "idx_companies_original_id", "original_id");
        ensureColumn("staffs", "apply_type", "VARCHAR(10) NOT NULL DEFAULT 'create' COMMENT '申请类型：create创建申请 / update修改申请（影子行）'");
        ensureColumn("staffs", "original_id", "BIGINT NULL COMMENT '修改申请影子行的原记录 id（apply_type=update 时有值）'");
        ensureIndex("staffs", "idx_staffs_original_id", "original_id");
        ensureColumn("characters", "apply_type", "VARCHAR(10) NOT NULL DEFAULT 'create' COMMENT '申请类型：create创建申请 / update修改申请（影子行）'");
        ensureColumn("characters", "original_id", "BIGINT NULL COMMENT '修改申请影子行的原记录 id（apply_type=update 时有值）'");
        ensureIndex("characters", "idx_characters_original_id", "original_id");
        // 帖子投票：schema.sql 已建三表，这里兜底旧库
        ensurePollTables();
        // 萌点系统：旧库 users 表补 moe_points 列（默认 0），并确保 daily_rewards 防重表存在
        ensureColumn("users", "moe_points", "INT NOT NULL DEFAULT 0");
        ensureTableIfAbsent("daily_rewards", DAILY_REWARDS_DDL);
        // Galgame 详情页评论：schema.sql 已建三表，这里兜底旧库
        ensureGalgameReplyTables();
        // 制作人员/角色库：schema.sql 已建六表，这里兜底旧库（并兜底补外键）
        ensureStaffCharacterTables();
        // Galgame-制作人员/角色关联的职责/定位描述：旧库 galgame_staff / galgame_character 补 description 列
        ensureColumn("galgame_staff", "description", "VARCHAR(200) NULL COMMENT '制作人员在作品中的职责/备注'");
        ensureColumn("galgame_character", "description", "VARCHAR(200) NULL COMMENT '角色在作品中的定位/备注'");
        // Galgame 标签系统：确保 tags / galgame_tag 表存在（schema.sql 已建，这里兜底旧库）
        ensureTableIfAbsent("tags", TAGS_DDL);
        ensureTableIfAbsent("galgame_tag", GALGAME_TAG_DDL);
        ensureFk("tags", "fk_tags_user", "FOREIGN KEY (created_by) REFERENCES users (id) ON DELETE SET NULL");
        ensureFk("galgame_tag", "fk_gt_galgame", "FOREIGN KEY (galgame_id) REFERENCES galgames (id) ON DELETE CASCADE");
        ensureFk("galgame_tag", "fk_gt_tag", "FOREIGN KEY (tag_id) REFERENCES tags (id) ON DELETE CASCADE");
        // 画廊多图 + 相关系列：schema.sql 已建两张新表，这里兜底旧库（并兜底补外键）
        ensureTableIfAbsent("galgame_images", GALGAME_IMAGES_DDL);
        ensureTableIfAbsent("galgame_related", GALGAME_RELATED_DDL);
        ensureFk("galgame_images", "fk_gi_galgame", "FOREIGN KEY (galgame_id) REFERENCES galgames (id) ON DELETE CASCADE");
        ensureFk("galgame_related", "fk_gr_galgame", "FOREIGN KEY (galgame_id) REFERENCES galgames (id) ON DELETE CASCADE");
        ensureFk("galgame_related", "fk_gr_related", "FOREIGN KEY (related_id) REFERENCES galgames (id) ON DELETE CASCADE");
        // 分类与标签彻底分离：清理之前播种的 gg-* 标签实体（alias IS NOT NULL），
        // 其迁入 galgame_tag 的旧分类关联由 tags 的 FK ON DELETE CASCADE 级联删除；
        // 旧分类系统 galgame_tags 表（galgame_id, section_key）保留不删，读写走 GalgameDao。
        // 幂等：重复启动无副作用（首次清理后已无 alias 标签）。
        jdbcTemplate.update("DELETE FROM tags WHERE alias IS NOT NULL");

        // 条目贡献者：确保 entity_contributors 表存在（schema.sql 已建，这里兜底旧库）+ FK + 存量回填。
        // 复合主键 (entry_type, entry_id, user_id) 天然防同一人重复贡献；用户删号由 FK ON DELETE CASCADE 级联清理。
        ensureTableIfAbsent("entity_contributors", ENTITY_CONTRIBUTORS_DDL);
        ensureFk("entity_contributors", "fk_ec_user", "FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE");
        backfillEntityContributors();

        recomputeLikeCounts();
    }

    /**
     * 确保点赞表为 (x_id, user_id) 新结构：
     * 表不存在则创建；仍是旧 visitor_id 结构则 DROP 后重建（清空旧访客点赞数据）。
     */
    private void ensureLikeTable(String table, String createDdl) {
        Integer tableExists = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.TABLES "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ?",
                Integer.class, table);
        if (tableExists == null || tableExists == 0) {
            jdbcTemplate.execute(createDdl);
            return;
        }
        Integer visitorCol = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.COLUMNS "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND COLUMN_NAME = 'visitor_id'",
                Integer.class, table);
        if (visitorCol != null && visitorCol > 0) {
            jdbcTemplate.execute("DROP TABLE " + table);
            jdbcTemplate.execute(createDdl);
        }
    }

    /**
     * 帖子投票：确保 polls / poll_options / poll_votes 三张表存在（schema.sql 会建，这里兜底旧库）。
     * 投票表无历史结构，只需建表，不需要 ensureLikeTable 的 visitor_id 结构检测。
     */
    private void ensurePollTables() {
        ensureTableIfAbsent("polls", POLLS_DDL);
        ensureTableIfAbsent("poll_options", POLL_OPTIONS_DDL);
        ensureTableIfAbsent("poll_votes", POLL_VOTES_DDL);
    }

    /**
     * Galgame 详情页评论：确保 galgame_replies / galgame_reply_likes / galgame_reply_dislikes 三张表存在
     * （schema.sql 会建，这里兜底旧库）。无历史结构，只需建表（同投票表）。
     */
    private void ensureGalgameReplyTables() {
        ensureTableIfAbsent("galgame_replies", GALGAME_REPLIES_DDL);
        ensureTableIfAbsent("galgame_reply_likes", GALGAME_REPLY_LIKES_DDL);
        ensureTableIfAbsent("galgame_reply_dislikes", GALGAME_REPLY_DISLIKES_DDL);
    }

    /**
     * 制作人员/角色库：确保 staffs / characters / galgame_staff / galgame_character / staff_character 五张表存在
     * （schema.sql 会建，这里兜底旧库）；再兜底补外键（表已存在但缺约束时补上，幂等）。
     */
    private void ensureStaffCharacterTables() {
        ensureTableIfAbsent("staffs", STAFFS_DDL);
        ensureTableIfAbsent("characters", CHARACTERS_DDL);
        ensureColumn("staffs", "image", "VARCHAR(500) NULL COMMENT '封面图 URL'");
        ensureColumn("characters", "image", "VARCHAR(500) NULL COMMENT '封面图 URL'");
        ensureTableIfAbsent("galgame_staff", GALGAME_STAFF_DDL);
        ensureTableIfAbsent("galgame_character", GALGAME_CHARACTER_DDL);
        ensureTableIfAbsent("staff_character", STAFF_CHARACTER_DDL);
        ensureFk("staffs", "fk_staffs_user", "FOREIGN KEY (created_by) REFERENCES users (id) ON DELETE SET NULL");
        ensureFk("characters", "fk_characters_user", "FOREIGN KEY (created_by) REFERENCES users (id) ON DELETE SET NULL");
        ensureFk("galgame_staff", "fk_gs_galgame", "FOREIGN KEY (galgame_id) REFERENCES galgames (id) ON DELETE CASCADE");
        ensureFk("galgame_staff", "fk_gs_staff", "FOREIGN KEY (staff_id) REFERENCES staffs (id) ON DELETE CASCADE");
        ensureFk("galgame_character", "fk_gc_galgame", "FOREIGN KEY (galgame_id) REFERENCES galgames (id) ON DELETE CASCADE");
        ensureFk("galgame_character", "fk_gc_character", "FOREIGN KEY (character_id) REFERENCES characters (id) ON DELETE CASCADE");
        ensureFk("staff_character", "fk_sc_staff", "FOREIGN KEY (staff_id) REFERENCES staffs (id) ON DELETE CASCADE");
        ensureFk("staff_character", "fk_sc_character", "FOREIGN KEY (character_id) REFERENCES characters (id) ON DELETE CASCADE");
    }

    private void ensureTableIfAbsent(String table, String createDdl) {
        Integer exists = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.TABLES "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ?",
                Integer.class, table);
        if (exists == null || exists == 0) {
            jdbcTemplate.execute(createDdl);
        }
    }

    /**
     * Galgame 评分（一人一票）：
     * <ul>
     *   <li>旧库 galgames.rating(DECIMAL(3,1)，未使用过) 迁移为 rating_avg(DECIMAL(4,2)，存精确两位小数)，新库直接建；</li>
     *   <li>新增 rating_count(评分人数)；</li>
     *   <li>确保 galgame_ratings 防重表存在（schema.sql 已建，这里兜底旧库），主键 (galgame_id, user_id) 一人一票；</li>
     *   <li>补 score 列存该用户评分（详情页已评分回显）；存量旧记录 score 为 NULL（当时未存分数，无法回补）。</li>
     * </ul>
     */
    private void migrateGalgameRating() {
        if (!columnExists("galgames", "rating_avg")) {
            if (columnExists("galgames", "rating")) {
                jdbcTemplate.execute("ALTER TABLE galgames CHANGE COLUMN rating rating_avg DECIMAL(4,2) NULL COMMENT '评分平均分（用户评分汇总，管理员不可写）'");
            } else {
                jdbcTemplate.execute("ALTER TABLE galgames ADD COLUMN rating_avg DECIMAL(4,2) NULL COMMENT '评分平均分（用户评分汇总，管理员不可写）'");
            }
        }
        ensureColumn("galgames", "rating_count", "INT NOT NULL DEFAULT 0");
        ensureLikeTable("galgame_ratings", GALGAME_RATINGS_DDL);
        // 评分回显：galgame_ratings 补 score 列（旧记录为 NULL，新评分写入分数）
        ensureColumn("galgame_ratings", "score", "DECIMAL(3,1) NULL");
    }

    private void ensureColumn(String table, String column, String ddl) {
        Integer exists = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.COLUMNS "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND COLUMN_NAME = ?",
                Integer.class, table, column);
        if (exists == null || exists == 0) {
            jdbcTemplate.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + ddl);
        }
    }

    /**
     * 多标签：确保 post_tags 关联表存在（schema.sql 会建，这里兜底旧库 / 降级场景）。
     */
    private void ensurePostTagsTable() {
        Integer exists = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.TABLES "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'post_tags'",
                Integer.class);
        if (exists == null || exists == 0) {
            jdbcTemplate.execute(
                    "CREATE TABLE post_tags ("
                            + "post_id BIGINT NOT NULL, section_key VARCHAR(32) NOT NULL, "
                            + "PRIMARY KEY (post_id, section_key), "
                            + "KEY idx_post_tags_section (section_key), "
                            + "CONSTRAINT fk_post_tags_post FOREIGN KEY (post_id) REFERENCES posts (id) ON DELETE CASCADE"
                            + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci");
        }
    }

    /**
     * 多标签：把旧库 posts.section 单值数据迁入 post_tags。
     * 仅在 posts 还有 section 列时执行（迁移完成后会删列，二次启动该列不存在则跳过）。
     */
    private void migratePostSectionsToTags() {
        if (!columnExists("posts", "section")) return;
        jdbcTemplate.update(
                "INSERT IGNORE INTO post_tags (post_id, section_key) "
                        + "SELECT id, section FROM posts WHERE section IS NOT NULL AND section <> ''");
    }

    private void dropColumnIfExists(String table, String column) {
        if (columnExists(table, column)) {
            jdbcTemplate.execute("ALTER TABLE " + table + " DROP COLUMN " + column);
        }
    }

    /**
     * 嵌套回复父作者快照回填：旧库已有的嵌套回复，从仍存活的父回复把作者名抄到 parent_author。
     * 只回填能 JOIN 到父回复的行；父已删除的历史数据无法回填，保持 NULL（前端回退处理）。
     */
    /**
     * 昵称回填：存量用户昵称初始化为账号名（新注册用户 insert 时已直接写 nickname=username）。
     */
    private void backfillNickname() {
        jdbcTemplate.update(
                "UPDATE users SET nickname = username WHERE nickname IS NULL OR nickname = ''");
    }

    private void backfillParentAuthor() {
        jdbcTemplate.update(
                "UPDATE replies r JOIN replies p ON r.parent_id = p.id "
                        + "SET r.parent_author = p.author "
                        + "WHERE r.parent_author IS NULL AND r.parent_id IS NOT NULL");
    }

    /**
     * 条目贡献者存量回填：四实体表已有创建者（created_by IS NOT NULL）的行 INSERT IGNORE 进 entity_contributors
     * （entry_type 分别 'galgame'/'company'/'staff'/'character'，entry_id=id，user_id=created_by，created_at=原 created_at）。
     * 幂等：INSERT IGNORE + 复合主键，重复启动不会插入重复贡献行。
     */
    private void backfillEntityContributors() {
        jdbcTemplate.update(
                "INSERT IGNORE INTO entity_contributors (entry_type, entry_id, user_id, created_at) "
                        + "SELECT 'galgame', id, created_by, created_at FROM galgames WHERE created_by IS NOT NULL");
        jdbcTemplate.update(
                "INSERT IGNORE INTO entity_contributors (entry_type, entry_id, user_id, created_at) "
                        + "SELECT 'company', id, created_by, created_at FROM companies WHERE created_by IS NOT NULL");
        jdbcTemplate.update(
                "INSERT IGNORE INTO entity_contributors (entry_type, entry_id, user_id, created_at) "
                        + "SELECT 'staff', id, created_by, created_at FROM staffs WHERE created_by IS NOT NULL");
        jdbcTemplate.update(
                "INSERT IGNORE INTO entity_contributors (entry_type, entry_id, user_id, created_at) "
                        + "SELECT 'character', id, created_by, created_at FROM characters WHERE created_by IS NOT NULL");
    }

    private boolean columnExists(String table, String column) {
        Integer exists = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.COLUMNS "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND COLUMN_NAME = ?",
                Integer.class, table, column);
        return exists != null && exists > 0;
    }

    private void ensureFk(String table, String constraint, String fkSql) {
        Integer exists = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS "
                        + "WHERE CONSTRAINT_SCHEMA = DATABASE() AND TABLE_NAME = ? AND CONSTRAINT_NAME = ?",
                Integer.class, table, constraint);
        if (exists == null || exists == 0) {
            jdbcTemplate.execute("ALTER TABLE " + table + " ADD CONSTRAINT " + constraint + " " + fkSql);
        }
    }

    private void ensureIndex(String table, String indexName, String columns) {
        Integer exists = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.STATISTICS "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND INDEX_NAME = ?",
                Integer.class, table, indexName);
        if (exists == null || exists == 0) {
            jdbcTemplate.execute("ALTER TABLE " + table + " ADD INDEX " + indexName + " (" + columns + ")");
        }
    }

    private void ensureUniqueIndex(String table, String indexName, String columns) {
        Integer exists = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.STATISTICS "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND INDEX_NAME = ?",
                Integer.class, table, indexName);
        if (exists == null || exists == 0) {
            jdbcTemplate.execute("ALTER TABLE " + table + " ADD UNIQUE INDEX " + indexName + " (" + columns + ")");
        }
    }

    private void recomputeLikeCounts() {
        jdbcTemplate.update(
                "UPDATE posts p LEFT JOIN "
                        + "(SELECT post_id, COUNT(*) AS c FROM post_likes GROUP BY post_id) t "
                        + "ON t.post_id = p.id SET p.like_count = COALESCE(t.c, 0)");
        jdbcTemplate.update(
                "UPDATE replies r LEFT JOIN "
                        + "(SELECT reply_id, COUNT(*) AS c FROM reply_likes GROUP BY reply_id) t "
                        + "ON t.reply_id = r.id SET r.like_count = COALESCE(t.c, 0)");
    }
}
