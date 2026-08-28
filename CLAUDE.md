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
- 接口：`GET /api/galgames`（列表 + q 名称搜索 + tags 多标签 **AND** 过滤，**只返回 approved**）、`GET /{id}`（详情）、`POST`（创建，登录用户，管理员→approved / 普通用户→pending）、`PUT /{id}`（编辑，`updated_at` 由 `ON UPDATE CURRENT_TIMESTAMP` 自动刷新；管理员可改一切，创建者仅 status≠approved 可改自己）、`DELETE /{id}`（管理员可删一切，创建者仅 pending 可删自己）、`POST /image`（封面上传，登录用户）。权限：401 未登录 / 403 无权 / 404（他人看非 approved）。
- 前端：`/galgame` 列表卡点击进 `/galgame/:id` 详情页（`GalgameDetailView.vue`），管理员在详情页**内联编辑**（表单复用 AddGalgameView 字段：名称/4 组标签/封面上传/简介/制作人员/资源链接）+ **删除**（`ElMessageBox.confirm` 确认）。

## Galgame 提交审核（已完成本地，2026-08-28，待部署）
- **流程**：普通登录用户在 `/galgame/new` 提交 galgame 信息 → 状态 `pending` 待审核；管理员在 Galgame 页顶栏「审核galgame信息」（`/galgame/review`）看待审列表 → 点进详情「通过审核」/「拒绝」（拒绝必填理由）。通过后公开上架；拒绝后提交者详情页可见 `reject_reason`。
- **数据**：`galgames` 加 3 列——`status VARCHAR(20) NOT NULL DEFAULT 'approved'`（存量数据即已上架）、`reject_reason VARCHAR(500) NULL`、`reviewed_at DATETIME NULL`；`schema.sql` + `DatabaseMigrator`（3 行 `ensureColumn`）双写。
- **后端**：`Galgame` model 加 `status/rejectReason/reviewedAt/creator`（creator 是 BASE_COLUMNS 子查询 `(SELECT COALESCE(nickname, username) FROM users u WHERE u.id = g.created_by)`，不 JOIN 避免影响行数）；`GalgameDao` 加 `findPending()`（status='pending' ORDER BY created_at DESC）+ `updateStatus(id, status, rejectReason)`（`SET status=?, reject_reason=?, reviewed_at=NOW()`）；`update()` 不碰 status 列。接口：`GET /api/galgames/pending`（管理员，含 creator）、`POST /api/galgames/{id}/review`（管理员，body `{status, reason?}`，rejected 无 reason → 400「请填写拒绝理由。」，reason≤500）；评分 `POST /{id}/rating` 仅 approved（pending/rejected → 400「该条目尚未通过审核，不能评分。」）；`GET /{id}` pending/rejected 仅创建者/管理员可见**且不** incrementView，否则 404。`/pending` 精确路径优先于 `/{id}` 模板不冲突。
- **前端**：路由加 `/galgame/review`（在 `:id` 前）；`GalgameView` 顶栏右侧 `gal-header-actions`（审核按钮 warning 色在左、添加按钮 `v-if="isLoggedIn"` 在右）；`AddGalgameView` 挡板改 `!loggedIn`（去登录带 redirect）、标题「提交 Galgame 信息」、成功文案按角色区分；`GalgameDetailView` 加状态 tag（pending→warning 待审核 / rejected→danger 已拒绝+理由 el-alert）、`canEdit`/`canDelete` computed（`isAdmin || (isCreator && status!=='approved')`）、管理员+pending 时「通过/拒绝」按钮、评分区仅 approved、meta 显示「提交人」；`GalgameReviewView.vue` 新建待审列表（非管理员挡板 + 空态「暂无待审核的 Galgame 提交」）。
- **「我的提交」**（2026-08-28 补，同批部署）：普通用户提交后公开列表看不到自己的 pending 条目，需顶栏「我的提交」按钮（`v-if="isLoggedIn"`，位于审核与添加按钮之间）进 `/galgame/mine` 页查看自己全部提交（含各状态标签 + 拒绝理由），点进详情即可编辑/删除。接口 `GET /api/galgames/mine`（登录，`findByCreator`：`WHERE created_by=? ORDER BY created_at DESC, id DESC` 兜底同秒）。
- 冒烟：`D:\claude code\review_smoke.py`（自建管理员 galgame1 认证 + 普通用户 A/B，20 项断言全 PASS：approve/reject/权限/编辑删除/评分限制/理由可见）；`D:\claude code\mine_smoke.py`（mine 接口 10 项全 PASS：未登录 401 / 全状态+reject_reason+creator / id 倒序 / 公开列表隔离）。

## 三个小功能：搜索文案 / 审核通过 +10 萌点 / +n 萌点屏幕提示（已完成本地与线上，2026-08-28）
- **搜索文案**：`SearchUserView.vue` 输入框 placeholder「输入用户名搜索…」→「输入用户名或昵称搜索…」（与 `UserDao.findByNameLike` 同时搜账号名+昵称的行为一致）。
- **审核通过给提交者 +10 萌点（防刷分）**：新增 `galgames.moe_awarded TINYINT(1) NOT NULL DEFAULT 0`（schema.sql + DatabaseMigrator `ensureColumn`），`GalgameDao.claimMoeAward(id)` 执行 `UPDATE galgames SET moe_awarded=1 WHERE id=? AND moe_awarded=0`——**原子置位，仅首次成功返回 true**（防并发/反复审核重复发放）。加分路径两处：
  - `doCreate`：管理员创建**即上架**（status=approved）→ 尝试 claimMoeAward → 成功则 `userDao.adjustMoePoints(userId, 10)`（管理员自己是提交者，同样 +10）。
  - `review`：审核通过（approved）→ 尝试 claimMoeAward → 成功则给 `saved.createdBy()` +10；响应 `Map` 追加 `moe_granted`——**仅当操作者==提交者才返回 10**，否则 0（审核别人时不给管理员自己弹提示）。rejected→approved 反复切换也不会重复加分（moe_awarded 已置 1）。
  - ⚠️ 这是项目第一条「**按条目只奖一次**」的原子防重逻辑（区别于签到/发帖的「每日一次」INSERT IGNORE），对应冒烟断言 `re-approve no double moe`、`reject -> no moe`、`admin own re-approve no double moe & moe_granted=0`。
- **+n 萌点屏幕提示（通用检测萌点变化，2026-08-28 重构）**（纯前端）：`frontend/src/utils/moeGain.js` 统一入口 `refreshMoe(knownMoe?)`——调用方已持有最新值（如 App.refreshUser 拉的 /auth/me）直接传，否则拉 `GET /auth/me`；与 localStorage 基线（`galforum_moe_baseline_<uid>`，**按用户 id 隔离防串号**）比较，**增量>0 就弹「+n萌点」**，并同步基线 + user store。首次无基线只建立不弹（避免把历史萌点误报为新增）。`MoeToast.vue` 订阅 `moeGain` ref 渲染 `.moe-toast`（fixed 顶 45% 居中，color #ff7d5c，font-size 30px，z-index 9999，pointer-events none，`moe-float` 动画 2s：scale-in → 停留 → translateY 上浮淡出，2s 后 setTimeout 隐藏），在 `App.vue` SettingsPanel 后挂载。
  - **触发点（所有加分源统一调 refreshMoe）**：`App.vue refreshUser`（登录态恢复建立基线）、`ProfileView` 签到 +10、`ComposeView` 发帖（每日首次 +10）、`PostView` 评论（每日首次 +5）、`GalgameDetailView` 审核通过（提交者=操作者时 +10，审核他人无变化不弹）、`AddGalgameView` 管理员创建即上架 +10、`GalgameMineView` 进入时（覆盖被动加分如被管理员审核通过）。
  - 非首次发帖/评论（每日奖励已领）时 delta=0 不弹，天然正确；萌点减少不弹。旧 showMoeGain/getMoeBaseline/setMoeBaseline 导出已移除，只剩 `showMoeGain` 供 refreshMoe 内部用。
  - 算法验证：`/tmp/moe_detect_test.js`（node 模拟基线检测 9 场景：首次建基线/签到/重复签到/发帖/评论/审核通过/减少不弹/多用户隔离）9/9 PASS。
- 冒烟：`D:\claude code\moe_smoke.py`（10 项全 PASS：初始 0、pending 不加分、approve 提交者+10、moe_granted=0（审核人≠提交人）、re-approve 不重复、reject 不加、管理员创建即上架+10、管理员自我 re-approve 不重复且 moe_granted=0）。⚠️ 服务器部署冒烟（部署时写）若从创建响应提取 id，`user` 对象里也有 `id`——必须先截 `"galgame":{...}` 段（同投票选项坑）。

## 首页筛选排序（已完成）
- 首页搜索框左侧「排序」按钮，4 种排序：`GET /api/posts?sort=`，取值 `time`（默认，时间倒序）/ `hot`（热度 = view + reply×3 + like×5 倒序）/ `likes`（点赞量倒序）/ `following`（只看我关注的人的帖子）。
- 后端：`PostFilter` record 加 `sort` 字段（`of(q, category, section, sections, sort)`）；`PostDao.findAll(filter, hidden, currentUserId)` 新增排序分支 + following 过滤（未登录或未关注时返回空列表，SQL 用 `user_id IN (SELECT following_id FROM follows WHERE follower_id = ?)` 子查询）；`PostController.list` 加 `@RequestParam sort` 并透传当前 uid。
- 前端：`HomeView.vue` 顶栏 `el-dropdown`「排序」按钮（含 `filter-arrow`/`filter-check` 样式），`sortBy` ref 默认 `time`，切换即 `load()`；未登录点「关注的人」提示先登录；following 空态文案「你关注的人还没有发帖。」。

## Galgame 页面排序（已完成，本地与线上 2026-08-28 已同步部署）
- 排序行在筛选区顶部：总浏览数 / 创建顺序 / 发售日期 / 评分；`GET /api/galgames?sort=`，取值 `created`（默认，管理员添加顺序倒序）/ `views`（浏览数倒序）/ `views_asc`（浏览数升序）/ `rating`（评分从高到低）/ `rating_asc`（评分从低到高）/ `release_date_desc`（发售日期从新到旧）/ `release_date_asc`（从旧到新）。
- **三键两态**：总浏览数 / 评分 / 发售日期 三个排序按钮都是两态切换——第一下倒序（从高到低/从新到旧，文字右侧紧贴 **↑**），再按一下升序（从低到高/从旧到新，箭头变 **↓**）；两态都算激活高亮；「创建顺序」单选无箭头。前端统一用 `SORT_DIR = { views: {desc:'views',asc:'views_asc'}, rating: {...}, release_date: {desc:'release_date_desc',asc:'release_date_asc'} }` 驱动 `setSort`/`isSortActive`/`sortArrowOf`，`load()` 传 `sort`。
- ⚠️ NULL 排最后：`GalgameDao.orderBy` 用 `(col IS NULL)` 做 ORDER BY 首键（false=0 在 true=1 前），未评分/无发售日期的条目恒排最后；rating 同分再按 `rating_count` 降序（asc 升序）；`view_count` 非空默认 0 无需 NULL 处理。
- 浏览数：`galgames.view_count`（DatabaseMigrator `ensureColumn` 迁移，schema.sql 已加默认 0），`GET /api/galgames/{id}` 详情访问 +1 并重查返回最新值；卡片显示「浏览 N」。
- 列表卡片 meta 区（`.gal-views`，flex 两段）显示「浏览 N」+「评分 X.X / 10」（无评分显示「暂无评分」）；列表接口返回 `rating_avg`/`rating_count`（同一 mapRow）。⚠️ 字段名必须是 `rating_avg`（旧 `rating` 已改名，用 `p.rating` 会恒为「暂无评分」）。
- 后端：`Galgame` model 加 `viewCount`；`GalgameDao` BASE_COLUMNS 加 `g.view_count`、`findAll(q, tags, sort)` + `orderBy` helper（含 views/views_asc/rating/rating_asc/release_date_desc/release_date_asc 分支）、`incrementView(id)`；`GalgameController.list` 加 `sort` 参数、`getById` 访问计数。
- 前端：`GalgameView.vue` 排序行 `gal-filter-btn` 复用筛选按钮样式，`sortBy` 默认 `created`；`.gal-sort-arrow` 样式 margin-left:4px。

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
- 列表页「发售日期/评分/总浏览数」排序**已启用**（见「Galgame 页面排序」一节），冒烟脚本 `D:\claude code\deploy_galgame_sort_to_server.py`（上传 jar+dist → 重启 8081 → 服务器插测试数据断言六种排序顺序 → 清理 → nginx assets 校验）。⚠️ 服务器部署重启后端三个坑（都记过代价）：
  1. **kill 与 start 必须分开两个 exec_command**——同一命令里 `ps -ef | grep '[g]algame'` 会匹配到当前 bash -c 自身（命令行含 galgame jar 名），kill 自杀导致 start 不执行（app.log 无新启动记录）。
  2. **start 命令必须带 `</dev/null & sleep 1; echo started`**——否则 paramiko channel 关闭会把新起的 java 一并带走（报「backend NOT up」但进程实际可能存活，先查 `ps -ef | grep '[j]ava'` + app.log）。
  3. **Python 字符串里 curl `-w '%{http_code}'` 用单 `%`，不能用 `%%`**——普通字符串 `%%` 就是两个字面 `%`，传给远程 curl 会输出字面 `%200`，永远匹配不上 `"200"`（诊断时输出 `code=[200]` 说明后端其实健康）。

## 昵称功能（已完成，本地与线上 2026-08-28 已同步）
- **存储**：`users.nickname` VARCHAR(32)（**可重复**，初始=账号名）。迁移：DatabaseMigrator `ensureColumn` + `backfillNickname()` 回填存量（`SET nickname=username WHERE NULL/''`），新注册 `UserDao.insert` 直接写 `nickname=username`。schema.sql 的 users 表已加列定义。
- **帖子/评论实时显示昵称**：PostDao / ReplyDao 查询 `LEFT JOIN users u ON u.id = posts.user_id`，author 用 `COALESCE(u.nickname, posts.author) AS author` 实时取昵称（改昵称全站生效，匿名帖 / 用户被删回退快照列）；回复的 `parent_author` 同理实时化（再 JOIN 父回复 `replies pr` + `users pu`）。
  - ⚠️ **LEFT JOIN 后裸列歧义**：users 也有 id/created_at，Base 列、WHERE、ORDER BY 必须加 `posts.`/`replies.` 前缀（findAll 的 q 搜索里 author 也要改成 `COALESCE(u.nickname, posts.author) LIKE ?`；findFavoritesByUser 独立 SQL 需单独 JOIN）。
- **写入快照**：发帖/回帖 `PostController` 的 author 用 `user.nickname()`。
- **改昵称**：`PUT /api/auth/profile` 带 `nickname`（部分更新，非空、按 `codePointCount` ≤32，可重复不查重）。
- **模型/DAO**：User / UserProfile record 加 `nickname`；UserDao / FollowDao 所有 SELECT 带 nickname 列；`findByNameLike` 同时搜账号名+昵称（`username LIKE ? OR nickname LIKE ?`）。
- **前端**：ProfileView 编辑资料区「昵称」输入框（保存 PUT /auth/profile 并 `setUser` 更新）；所有显示用户名的位置（导航栏 / 搜索用户 / 关注列表 / 私聊 / 通知 / 收藏页）统一「昵称优先、账号名兜底」`X.nickname || X.username`。
- **部署脚本**：`D:\claude code\deploy_galgame_nickname.py`（paramiko 上传 jar + dist 原子替换 + 重启 8081 + 轮询验证），含服务器凭据仅本地使用不外传。

## 四功能：点踩 / emoji / 私信撤回 / 帖子封面（已完成，本地与线上 2026-08-28 同步部署）
- **点踩**（帖子 + 评论）：表 `post_dislikes` / `reply_dislikes`（仿点赞表），列 `posts.dislike_count` / `replies.dislike_count`。**与点赞独立不互斥**（可同时赞和踩）。接口 `POST /api/posts/{id}/dislike`、`POST /api/replies/{id}/dislike`（拦截器已注册，未登录 401），返回 `{disliked, dislike_count}`，toggle 减用 `GREATEST(0, ...)`。前端按钮灰色区分粉色点赞。
- **emoji**：纯前端（帖子正文 / 评论 / 私聊输入区），`EmojiPicker.vue` 组件（160 个、8 列网格、`emit('pick')` 追加到草稿）。内容字段 utf8mb4 直接存 emoji 字符，无需后端改动。
- **私信撤回**：列 `dm_messages.is_recalled`，接口 `POST /api/dm/messages/{id}/recall`（手动鉴权 requireLogin，未登录 401 / 非发送者 403「只能撤回自己发送的消息。」/ 不存在 404）。**两分钟窗口**：`created_at` 距今 > 2 分钟 → 403「发送超过两分钟，无法撤回。」；已撤回幂等返回当前消息不报错。前端 `ConversationView.vue` 撤回按钮 `v-if="canRecall(m)"`（自己发的 + 未撤回 + 距今 ≤ 2 分钟，4 秒轮询刷新后按钮自动消失）；已撤回气泡灰色斜体「已撤回」；`MessagesView` 会话预览显示「[已撤回]」。
- **帖子封面**：列 `posts.cover_image VARCHAR(500) NULL`，发帖 multipart 可选 `cover` 单文件（≤8MB，png/jpg/jpeg/webp/gif），存 `uploads/post_<id>/cover_<ts>_<uuid>.<ext>`（与附件同目录），发帖后回填 `updateCoverImage`。删帖时 `deleteAttachmentFiles` 一并清理。前端：发帖页封面上传（预览/移除/revokeObjectURL）；首页卡片缩略图与详情页封面均 `width:100%; height:auto`（左右和内容区同宽、上下自适应，整图完整显示不裁剪，不用 object-fit）。
- 冒烟脚本：`D:\claude code\smoke_test_4features.py`（注册两临时用户实测四功能 + 超时撤回 + 权限 401/403 + 幂等）。
- 部署脚本：`D:\claude code\deploy_galgame_features.py`（上传 jar+dist → 重启 8081 → 验证迁移 → 服务器本地 curl 冒烟 10 项），部署时已验证服务器 `post_dislikes/reply_dislikes` 表 + 4 新列全就位、Nginx 8080 反代正常。

## 置顶功能：管理员帖子置顶 + 仅楼主评论置顶（本地与线上 2026-08-28 同步部署）
- **帖子置顶**（`posts.pinned_until DATETIME NULL`，管理员设置截止时间、到期自动取消）：
  - 接口 `PUT /api/posts/{id}/pin` body `{days}`：days>0 置顶 N 天返回 `{ok, pinned_until}`；days=0 取消返回 `{ok, pinned_until:null}`；days<0 400「置顶天数不能为负。」；>3650 400「置顶天数不能超过 3650 天。」。仅管理员（admin_level≥1）：未登录 401 / 非管理员 403「需要管理员权限。」。拦截器已注册 `/api/posts/*/pin`。
  - 排序：`PostDao.findAll` 所有排序分支统一加前缀 `(posts.pinned_until IS NOT NULL AND posts.pinned_until > NOW()) DESC`（置顶中优先、过期自动失效）；收藏页 `findFavoritesByUser` 同样置顶优先。
  - 前端：HomeView / SectionView 帖子卡片右上角 `.pin-area`（管理员显示「置顶/取消置顶」按钮——未置顶 ElMessageBox.prompt 输正整数天数，已置顶 confirm 取消）；FavoritesView / ProfileView 仅显示红色「置顶」标签。`isPinned(p)` 工具函数（`utils/format.js`：`pinned_until` 存在且 > now）。
- **评论置顶**（`replies.is_pinned TINYINT(1) NOT NULL DEFAULT 0`，仅帖子楼主、无时间限制）：
  - 接口 `PUT /api/replies/{id}/pin` body `{pinned}`：仅楼主（`post.user_id == 当前uid`），**管理员若非楼主同样无权**，否则 403「只有帖子楼主可以置顶评论。」，返回 `{ok, pinned}`。拦截器已注册 `/api/replies/*/pin`。
  - 排序：`ReplyDao.findByPostId` → `ORDER BY replies.is_pinned DESC, replies.created_at ASC, replies.id ASC`（置顶排最前）。
  - 前端：PostView 评论区 `.reply-top` 红色置顶标签 + 按钮 `canPinReply(r)`（**仅楼主显示，楼主自己发的评论同样可置顶**）；toggle 后局部刷新 `is_pinned`。
  - ⚠️ JSON 键坑：Reply record 的 pinned 组件必须加 `@JsonProperty("is_pinned")`（否则 SNAKE_CASE 输出 `pinned`，前端读 `r.is_pinned` 会失效）。
- **模型/DAO**：Post 加 `LocalDateTime pinnedUntil`（NON_NULL 下未置顶不序列化）；Reply 加 `boolean pinned`；RecentPost 加 `pinnedUntil`（资料页近期帖显示标签）。
- **迁移**：DatabaseMigrator `ensureColumn("posts","pinned_until","DATETIME NULL")` + `ensureColumn("replies","is_pinned","TINYINT(1) NOT NULL DEFAULT 0")`，schema.sql 同步加列（幂等）。
- 冒烟脚本：`D:\claude code\smoke_test_pin.py`（28 项：401/403 权限、普通楼主置顶评论、管理员非楼主置顶评论 403、楼主自己评论可置顶、帖子置顶/取消/非法天数、置顶优先排序）。本地已 28/28 通过。
- 部署脚本：`D:\claude code\deploy_pin_to_server.py`（上传 jar+dist → 重启 8081 → 验证 2 列迁移 → 服务器 curl 冒烟 14 项），部署时已验证服务器 `posts.pinned_until`/`replies.is_pinned` 就位、公网 8080 反代正常。

## 投票功能：帖子投票（本地与线上 2026-08-28 同步部署，行为对齐 kungal 论坛）
- **3 张新表**：`polls`（主表：title/description/type(single|multiple)/min_choice/max_choice/deadline/status(result_visibility: always|after_vote|after_deadline)/is_anonymous/can_change_vote/post_id/user_id，user_id **NOT NULL + ON DELETE CASCADE**）；`poll_options`（text + vote_count 冗余计数）；`poll_votes`（唯一键 `(poll_id, option_id, user_id)` 防重，FK 全级联）。
- **接口**（6 个，`PollController`）：`GET /api/posts/{id}/polls`（可选登录）、`POST /api/posts/{id}/polls`（创建，仅楼主）、`PUT /api/polls/{id}`（编辑，仅楼主）、`DELETE /api/polls/{id}`（仅楼主）、`POST /api/polls/{id}/vote`（body `{option_id_array:[]}`，登录）、`GET /api/polls/{id}/logs?page&page_size`（仅楼主）。**所有 poll 路径不注册拦截器**，Controller 手动 `tokenService.resolveUserId(Authorization)` 鉴权。
- **权限（延续评论置顶规则）**：仅帖子楼主可管理投票（创建/编辑/删除/日志），**管理员非楼主同样 403**「只有楼主可以管理投票。」；投票需登录；列表公开。楼主判断 `post.user_id == 当前uid`。
- **canViewResults**（结果可见性，`PollController.canViewResults`）：楼主恒可见；结束（status=closed 或过 deadline）可见；否则按 result_visibility：always 可见 / after_vote 需已投 / after_deadline 需已结束。结果不可见时：`options[].vote_count` 省略、`vote_count`/`voters` 省略（NON_NULL）。
- **匿名投票**：`voters`（前 5 个投票人头像）一律省略，但 `voters_count`（去重人数）/票数照常返回；日志不返回 user。
- **投票/改票事务**：`PollDao.vote` 先删旧票逐选项减 `vote_count`，再插新票加计数；不可改票（can_change_vote=false）重复投 400「该投票不可修改投票。」。编辑时**已有票的选项不能改文本/删除** 400；can_change_vote=false 时只允许改标量。
- **模型/DAO**：`Poll` record（vote_count 用 `Integer` 可空 + NON_NULL，不可见时省略）；`PollOption` record（id/text/vote_count(Integer)/is_voted）；`PollDao`（BASE_COLUMNS + RowMapper + @Transactional + GeneratedKeyHolder，批量查 users 防 N+1）。
- **前端**（`frontend/src/components/`）：`PostPoll.vue`（投票区容器，独立 `GET /posts/{id}/polls` 拉取，仅 isOwner 显示创建入口）。**无投票时不渲染投票栏**：非楼主整个组件隐藏；楼主仅显示居中小「创建投票」按钮（`poll-create-inline`，2026-08-28 优化，避免空卡片占大位置）、`PollItem.vue`（单选 el-radio / 多选 el-checkbox、进度条+票数+百分比、投票/改票、结果可见性判断、楼主编辑/日志/删除）、`PollCreatorDialog.vue`（创建/编辑共用，编辑只发 add/update/delete）、`PollLogDialog.vue`（分页日志）。PostView 正文卡与评论卡之间挂 `<PostPoll :post-id :is-owner>`。结果可见性前端判断与后端 canViewResults 一致。
- **迁移**：schema.sql 建 3 表 + DatabaseMigrator `ensurePollTables()`（ensureTableIfAbsent 兜底旧库）。
- 冒烟脚本：`D:\claude code\smoke_test_poll.py`（76 项：权限/单选多选 min/max/投票改票计数/三种结果可见性/匿名/截止关闭拒绝/选项锁/日志分页/级联清理）。本地 76/76 通过。
- 部署脚本：`D:\claude code\deploy_poll_to_server.py`（上传 jar+dist → 重启 8081 → 验证 3 表迁移 → 服务器 curl 冒烟 18 项），部署时已验证服务器 3 表就位、公网 8080 反代正常。⚠️ 服务器 bash 冒烟里从创建响应提取选项 id 时**必须先截出 `"options":[...]` 段再取 id**（user 对象里也有 `"id"`，直接 grep 全 JSON 会拿到楼主 user id 导致投票 400「存在无效的选项。」）。

## 萌点(积分)系统 + 每日签到（本地与线上 2026-08-28 已同步部署）
- **数据**：`users` 加列 `moe_points INT NOT NULL DEFAULT 0`（DatabaseMigrator `ensureColumn` 兜底旧库）；新表 `daily_rewards`（`user_id/action_type('check_in'|'post'|'reply')/action_date`，**唯一键 `(user_id, action_type, action_date)` + INSERT IGNORE 防每日重复奖励**，并发安全，FK 用户级联删）。
- **接口**（`CheckInController`，路径未注册拦截器、手动 `tokenService.resolveUserId` 鉴权，未登录 401「请先登录。」）：`POST /api/check-in`（每日首次 +10，**幂等**：今天已签过返回 `{ok:true, awarded:false}` HTTP 200 不重复加分；成功 `{ok:true, awarded:true, moe_points:N}`）、`GET /api/check-in/status`（`{today_checked_in, moe_points}`）。
- **发帖/评论奖励**：`PostController.doCreatePost` 成功后 `claimDailyReward(userId,"post",10)`、`doCreateReply` 成功后 `claimDailyReward(userId,"reply",5)`；**每日首次各奖一次**；`try-catch` 静默容错（奖励失败不阻断发帖/评论）。
- **增减萌点留的接口（未来消耗功能用）**：`UserDao.adjustMoePoints(userId, delta)`（`GREATEST(0, moe_points+delta)`，负值安全）；`UserDao.claimDailyReward(userId, actionType, amount)`（可扩展新每日奖励类型）。
- **返回结构**：User（登录/注册/me）与 UserProfile（`GET /api/users/{id}`）均新增 `moe_points`。⚠️ `FollowDao` 也有一份 USER_ROW_MAPPER + 2 条 SELECT，加列时**必须同步补 `moe_points`**（否则编译失败）。
- **前端**：`ProfileView.vue` 资料卡右上角 `.moe-box`（margin-left:auto）显示「✦ 萌点 N」；本人可见签到按钮（未签「签到 +10」/已签禁用「今日已签到」）；load 时本人调 `GET /check-in/status`，签到成功更新 profile 与 user store；深色模式 theme.css 补 `html.dark .moe-box`。
- 冒烟脚本：`D:\claude code\smoke_test_moe.py`（17 项：注册/me/profile 的 moe_points=0、签到幂等、status、发帖/评论每日首次奖励、重复不加、未登录 401），本地 17/17 通过。
- 部署脚本：`D:\claude code\deploy_moe_to_server.py`（上传 jar+dist → 重启 8081 → 验证迁移「1 1」→ 服务器 curl 冒烟 17 项），部署时验证通过、线上 8080 反代正常，冒烟测试帖/测试用户已清理。⚠️ 服务器 bash 冒烟里提取用户 id 的变量**不能用 `UID`**（bash 只读保留变量，赋值会失败导致 curl 请求 `/users/0` 404）；改用 `NEWID`。

## @ 自动补全（本地与线上 2026-08-28 已同步部署）
- **需求**：发帖正文 / 评论输入框里打出 `@` → 弹出当前用户**关注的人**的列表（昵称 + 头像 + @用户名），鼠标/键盘（↑↓ Enter Esc）选中后，在光标处插入 `@用户名 `，发送后由现有 MentionService 解析触发对方通知。
- **后端**（`FollowController.toFollowResponse`）：关注/粉丝列表接口补 `m.put("nickname", u.nickname())`（原来只有 username，前端无法显示昵称）。⚠️ **修了一个隐藏 bug**：`FollowDao.findFollowing/findFollowers` 两条 SQL 拼接处 `u.created_at"` 与 `"FROM` **之间缺空格**（拼成 `created_atFROM`），任何 `GET /api/users/{id}/following|followers` 都 500；已加空格修复。
- **前端**：新组件 `frontend/src/components/MentionTextarea.vue`——原生 `<textarea>`（便于 `setSelectionRange` 控光标）+ @ 触发检测（`selectionStart` 前最后一个 `@`；@ 前是词字符→忽略邮箱/URL；@ 后含空白/非法字符→结束）；onMounted 拉 `/users/{我的id}/following`（未登录不拉、不弹）；下拉列表过滤 nickname/username，`@mousedown.prevent` 防失焦 + blur 延迟 150ms 关闭；插入 `@用户名 ` 后 nextTick 定位光标。props 与 el-input textarea 对齐（modelValue/placeholder/rows/maxlength/showWordLimit，字数统计右下角）。
- **集成**：`ComposeView.vue` 发帖正文、`PostView.vue` 评论框两处 `<el-input type="textarea">` 替换为 `<MentionTextarea>`（私信输入框不改，@ 通知只在发帖/评论解析）。深色模式 theme.css 补 `html.dark .mention-*` 条目。
- **前端两个坑（已修）**：① **@ 触发晚一拍**——`onInput` 里 `emit` 后立刻读 `props.modelValue` 是旧值（Vue props 下一 tick 才更新），只输入 `@` 检测不到；改用 `e.target.value`（DOM 同步值）。② **正文输入框变窄**——`el-form-item__content` 是 flex 容器，`.mention-textarea` 缺 `width: 100%` 导致收缩成内容宽（小方框）；已加 `width: 100%`。
- 冒烟：本地 Python 9 项（注册两人→各自设中文昵称→B 关注 A→`GET /users/B/following` 含 A 且带 A 昵称/avatar_url→`GET /users/A/followers` 含 B 带 B 昵称），9/9 通过。⚠️ 本地 curl 冒烟发中文 JSON body 会 400（Windows 终端编码问题），改用 Python urllib（UTF-8）或 JSON `\uXXXX` 转义。
- 部署：`D:\claude code\deploy_mention_to_server.py`（上传 jar+dist → 重启 8081 → 服务器 curl 冒烟 6 项：注册/设中文昵称/关注/following 带 nickname/followers 带 nickname），部署时 6/6 通过、公网 8080 反代正常、测试用户已清理。⚠️ 服务器 bash 冒烟里**用户变量不能叫 `A`/`B`**——`B=URL` 定义 API 地址后又 `B="mnt_b_$TS"` 覆盖，导致所有 `$B/auth/...` 变 404（与 UID 变量坑同类）；用 `UA`/`UB`。

## 服务器部署（腾讯云线上）
- **架构**：腾讯云 `62.234.76.178`（域名 `dnf88896.site`，备案待办，通过后切 80 + HTTPS）。Nginx（8080）托管 `frontend/dist` + 反代 `/api/`、`/uploads/` → 后端 jar（8081）→ MySQL 8.4（3306，库 `galgame`）。部署目录 `/opt/galgame/{frontend/dist, backend/{jar, app.log, uploads}}`。
- **更新流程**：本地 `mvn -q -DskipTests package` 打包 → 用 paramiko 脚本（参考 Temp 下 deploy 脚本）上传新 jar / dist → 重启后端：先杀旧进程再 `cd /opt/galgame/backend && nohup java -jar galgame-backend-0.0.1-SNAPSHOT.jar --server.port=8081 > app.log 2>&1 &`。
  - ⚠️ **只带 `--server.port=8081`，不要带 CORS 参数**：jar 默认已全放行（见下）。
  - ⚠️ **pkill 自杀坑**：`pkill -f galgame-backend` 会匹配到执行命令的 shell 自身（命令行含该字符串）导致命令卡死。改用 `for pid in $(ps -ef | grep '[g]algame' | awk '{print $2}'); do kill $pid; done`。
- **CORS 约定（2026-08-28 统一）**：本地与服务器后端代码都默认 `*` 全放行（`application.properties` 的 `app.cors.allowed-origins=*` + `WebConfig.java` 的 `@Value` 默认值 `*`）。**改 CORS 就改代码、两边一起部署，不要再靠启动参数 `--app.cors.allowed-origins=*` 临时覆盖**。Nginx 保留 `proxy_set_header Origin "";`：线上 API 只允许同源读取（跨域访问读不到响应，同源使用无影响）。
- **数据库备份**：安全备份在服务器 `/root/backup/galgame_dump_YYYYMMDD.sql`（`mysqldump -ugalgame -pgalgame123 --single-transaction --no-tablespaces galgame`，权限 600）。旧敏感备份已删除，**不要再往 `/opt/galgame` 放备份文件**。
- **安全现状**：腾讯云安全组挡 3306/8081 公网入口（真防线，8081/3306 公网不可达）；宝塔面板已关（8888 无监听）；旧 jar 备份 `/root/backup/galgame-backend-OLD-20260828.jar`。MySQL 账号 `galgame`（密码与本地 `application.properties` 默认一致）。root SSH / API key 等敏感凭据只在服务器配置与本地脚本中使用，**绝不在任何文档/对话中外传**。
- **只读审计 Claude Code**：服务器上有审计账号 `audit`（工作区 `/opt/audit-workspace`，DeepSeek 接口），可作主会话只读子代理查服务/日志/数据库/找 bug。调用方式见全局记忆 [[server-audit-claude-code]]。
