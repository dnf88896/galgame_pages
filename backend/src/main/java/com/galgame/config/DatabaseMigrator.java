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

    private static final String POST_FAVORITES_DDL =
            "CREATE TABLE post_favorites ("
                    + "post_id BIGINT NOT NULL, user_id BIGINT NOT NULL, "
                    + "created_at DATETIME DEFAULT CURRENT_TIMESTAMP, "
                    + "PRIMARY KEY (post_id, user_id), "
                    + "KEY idx_post_favorites_user (user_id), "
                    + "CONSTRAINT fk_post_favorites_post FOREIGN KEY (post_id) REFERENCES posts (id) ON DELETE CASCADE, "
                    + "CONSTRAINT fk_post_favorites_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE"
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
    private void backfillParentAuthor() {
        jdbcTemplate.update(
                "UPDATE replies r JOIN replies p ON r.parent_id = p.id "
                        + "SET r.parent_author = p.author "
                        + "WHERE r.parent_author IS NULL AND r.parent_id IS NOT NULL");
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
