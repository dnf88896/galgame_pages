# Galgame 论坛项目（galgame_pages）

技术栈：**Vue3 + Element Plus**（`frontend/`，dev server 5173）/ **Spring Boot 4.1 + JDBC**（`backend/`，jar 8080）/ **MySQL 8.4**（库名 `galgame`）。后端 schema 由 `schema.sql` 启动时自动执行（`CREATE TABLE IF NOT EXISTS` 幂等），共 15 张表。前端工具与 MySQL 都装在 `C:\Users\yinsh\dev-tools`。


## 启动与重启
- **MySQL**：数据目录在**项目内** `database/mysql-data`，配置 `database/my.ini`（basedir 指向 `C:\Users\yinsh\dev-tools\mysql`），用 `mysqld --defaults-file=...\database\my.ini` 后台启动（本机非管理员，无服务）。完整启动步骤见 `README.md`。
- **后端重启流程**：`netstat -ano | grep ':8080' | grep -i listen` 拿 PID → `taskkill //F //T //PID <pid>` → 在 `backend/` 跑 `mvn -q -DskipTests package` → 后台 `java -jar target/galgame-backend-0.0.1-SNAPSHOT.jar > app.log 2>&1` → `curl http://localhost:8080/api/boards` 轮询到 200。
- **前端**：`npm run dev`（5173），Vite HMR 实时生效。

## 后端关键约定
- ⚠️ **JSON 依赖是 Jackson 3**（`tools.jackson.*` 包名，非旧版 `com.fasterxml.jackson.*`）：手写 Jackson 代码/导入必须用 `tools.jackson.databind.json.JsonMapper`、`tools.jackson.core.type.TypeReference`；Jackson 3 读写方法抛**运行时异常**，不抛受检 `IOException`（catch `IOException` 会编译报错）。曾因此让子代理按 Jackson 2 写导入导致编译失败。
- `spring.jackson.property-naming-strategy=SNAKE_CASE` 自动处理 record → snake_case JSON。
- 表结构：新表 `galgames`/`galgame_tags`（Galgame 作品库）由 schema.sql 每次启动自动建（IF NOT EXISTS 幂等），**无需改 DatabaseMigrator**。

## Galgame 作品库（已完成完整 CRUD）
- 接口：`GET /api/galgames`（列表 + q 名称搜索 + tags 多标签 **AND** 过滤）、`GET /{id}`（详情）、`POST`（创建）、`PUT /{id}`（编辑，`updated_at` 由 `ON UPDATE CURRENT_TIMESTAMP` 自动刷新）、`DELETE /{id}`、`POST /image`（封面上传）。创建/编辑/删除/上传均需管理员（401 未登录 / 403 非管理员）。
- 前端：`/galgame` 列表卡点击进 `/galgame/:id` 详情页（`GalgameDetailView.vue`），管理员在详情页**内联编辑**（表单复用 AddGalgameView 字段：名称/4 组标签/封面上传/简介/制作人员/资源链接）+ **删除**（`ElMessageBox.confirm` 确认）。

## 首页筛选排序（已完成）
- 首页搜索框左侧「排序」按钮，4 种排序：`GET /api/posts?sort=`，取值 `time`（默认，时间倒序）/ `hot`（热度 = view + reply×3 + like×5 倒序）/ `likes`（点赞量倒序）/ `following`（只看我关注的人的帖子）。
- 后端：`PostFilter` record 加 `sort` 字段（`of(q, category, section, sections, sort)`）；`PostDao.findAll(filter, hidden, currentUserId)` 新增排序分支 + following 过滤（未登录或未关注时返回空列表，SQL 用 `user_id IN (SELECT following_id FROM follows WHERE follower_id = ?)` 子查询）；`PostController.list` 加 `@RequestParam sort` 并透传当前 uid。
- 前端：`HomeView.vue` 顶栏 `el-dropdown`「排序」按钮（含 `filter-arrow`/`filter-check` 样式），`sortBy` ref 默认 `time`，切换即 `load()`；未登录点「关注的人」提示先登录；following 空态文案「你关注的人还没有发帖。」。

## Galgame 页面排序（已完成）
- 排序行在筛选区顶部：总浏览数 / 创建顺序 / 发售日期 / 评分；`GET /api/galgames?sort=`，取值 `views`（浏览数倒序）/ `created`（默认，管理员添加顺序倒序）/ `release_date`、`rating`（**接口预留**，字段未实现，回退默认排序不报错）。
- 浏览数：`galgames.view_count`（DatabaseMigrator `ensureColumn` 迁移，schema.sql 已加默认 0），`GET /api/galgames/{id}` 详情访问 +1 并重查返回最新值；卡片显示「浏览 N」。
- 列表卡片 meta 区（`.gal-views`，flex 两段）显示「浏览 N」+「评分 X.X / 10」（无评分显示「暂无评分」）；列表接口返回 `rating_avg`/`rating_count`（同一 mapRow）。⚠️ 字段名必须是 `rating_avg`（旧 `rating` 已改名，用 `p.rating` 会恒为「暂无评分」）。
- 后端：`Galgame` model 加 `viewCount`；`GalgameDao` BASE_COLUMNS 加 `g.view_count`、`findAll(q, tags, sort)` + `orderBy` helper、`incrementView(id)`；`GalgameController.list` 加 `sort` 参数、`getById` 访问计数。
- 前端：`GalgameView.vue` 排序行 `gal-filter-btn` 复用筛选按钮样式，`sortBy` 默认 `created`；发售日期/评分点击提示「暂未上线，敬请期待」；`load()` 传 `sort`。

## Galgame 详情页发售日期 + 评分（已完成）
- **发售日期**（`galgames.release_date DATE NULL`，管理员可创建/编辑）：详情页与新建页都有 el-date-picker（`value-format="YYYY-MM-DD"`，中文 locale 在 `main.js` 用 `app.use(ElementPlus, { locale: zhCn })`）。
- **评分**（`galgames.rating_avg DECIMAL(4,2)` + `rating_count INT NOT NULL DEFAULT 0`，**管理员不可写**）：任何登录用户可给 galgame 打 0~10 分（满分 10，el-rate 可半星），**一人一票**不能重复评。
  - 存储模型：只存「上一个平均分 + 总人数」，新评分增量更新 `new_avg = (old_avg * old_count + score) / (count+1)`（首次直接 = score）；DB 存精确两位小数，显示最多一位。
  - 防重：`galgame_ratings(galgame_id, user_id)` 主键表**只记「谁评过」不含分数**，`rate()` 捕获 `DuplicateKeyException` → 409「你已经评过分了。」。
  - 接口：`POST /api/galgames/{id}/rating` body `{score}`（401 未登录 / 404 不存在 / 400 越界或非数字）；`GET /{id}` 带登录态返回 `rated`（前端据此禁用评分）。
  - 前端：`GalgameDetailView.vue` 展示「评分 X.X / 10（N 人评分）」+ el-rate（max=10 allow-half）+「提交评分」按钮，已评过禁用显示「你已评过分」。
- ⚠️ **SNAKE_CASE 契约坑**：后端 `spring.jackson.property-naming-strategy=SNAKE_CASE` 下，`releaseDate` 这类多词 record 字段对应 JSON 键是 `release_date`。前端 payload 必须发 `release_date`（`releaseDate` 会被 Jackson 忽略 → DB 写 NULL）。单字段名（name/tags/links 等）camelCase=snake_case 不受影响。
- 迁移：`schema.sql` galgames 加 `release_date`/`rating_avg`/`rating_count`；`DatabaseMigrator.migrateGalgameRating()` 把旧 `rating` 列 `CHANGE COLUMN` 成 `rating_avg`（新库直接建），并兜底建 `galgame_ratings`。
- 后端：`Galgame` model 字段 `... viewCount, releaseDate, ratingAvg, ratingCount`；`GalgameDao` BASE_COLUMNS 含 `g.rating_avg, g.rating_count`、`mapRow` 读两列、新增 `rate()`/`hasRated()`；`GalgameController` 注入 JsonMapper、`getById` 返回 Galgame 转 Map 加 `rated`、新增评分接口、创建/编辑校验 releaseDate 格式（非法 400「发售日期格式应为 YYYY-MM-DD。」，可空）。
- 前端：`GalgameDetailView.vue` 展示「发售：YYYY-MM-DD」，编辑模式与 `AddGalgameView.vue` 都加了 date-picker（enterEdit 回填 `d.release_date`）。
- ⏳ 列表页 `GalgameView.vue` 的「发售日期/评分」排序目前仍是预留提示，字段已实现，要启用只需在 `GalgameDao.orderBy` 加 `release_date`/`rating_avg` 分支 + 前端去掉提示。
