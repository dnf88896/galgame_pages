# Galgame 论坛项目（galgame_pages）

技术栈：**Vue3 + Element Plus**（`frontend/`，dev server 5173）/ **Spring Boot 4.1 + JDBC**（`backend/`，jar 8080）/ **MySQL 8.4**（库名 `galgame`）。后端 schema 由 `schema.sql` 启动时自动执行（`CREATE TABLE IF NOT EXISTS` 幂等），共 17 张表。前端工具与 MySQL 都装在 `C:\Users\yinsh\dev-tools`。

旧项目 `C:\Users\yinsh\Desktop\codex\galgame_pages` 已作废（保留未删）。

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

## 收藏夹（已完成）
- **默认公开**，用户可在个人资料页通过 `hide_favorites` 开关自行隐藏。
- 表 `post_favorites`（PK `post_id,user_id`，级联删）+ `users.hide_favorites TINYINT NOT NULL DEFAULT 0`（由 DatabaseMigrator `ensureColumn` 补列 + `ensureLikeTable` 建表，幂等）。
- 接口：`POST /posts/{id}/favorite`（toggle，返回 `{favorited}`，404 帖子不存在，需登录）、`GET /posts/{id}` 返回带 `favorited`（需登录才有值）、`GET /users/{id}/favorites`（用户收藏列表；隐藏时非本人/未登录 → 403 `该用户已隐藏收藏。`，404 `用户不存在。`）、`GET /users/{id}` 含 `favorite_count` / `hide_favorites`。
- ⚠️ `PUT /auth/profile` 是**部分更新**语义：body 里出现哪个字段就只更新哪个（`bio` / `hide_favorites` 都缺省不动），前端 saveBio 与隐私开关都同时发送两个字段，互不覆盖。
- 前端：PostView 帖子右下 ☆/★ 收藏按钮（金色 #e6a23c）；ProfileView 统计区「收藏」链接 + 隐私 el-switch；FavoritesView（`/user/:id/favorites`）展示收藏列表，本人可取消收藏。

## 修改密码（已完成）
- 接口 `PUT /api/auth/password`（需登录，WebConfig 已注册）：body `{old_password, new_password}`，校验旧密码（`UserDao.findPasswordHashById` + `PasswordEncoder.matches`，错误 400 `旧密码错误。`）、新密码 ≥6 位（400 `密码至少 6 位。`），成功后 `UserDao.updatePassword` 更新哈希，并 `AuthTokenDao.deleteByUser` **删除该用户全部 token**（所有旧会话失效）。
- 前端：ProfileView 编辑区底部「修改密码」按钮 + el-dialog（旧/新/确认密码，前端校验两次一致），成功后 `clearToken()` 跳 `/login` 重新登录。

## 举报系统（已完成：举报 + 管理员查看 + 受理处理）
- 表 `reports`（id、reporter_id、target_type 'post'/'reply'、target_id、reason、created_at、**status** TINYINT 0=待处理/1=已删除/2=已忽略、**handled_at**、**result**；UNIQUE(reporter_id,target_type,target_id) 同一人同一目标只记一条）。目标无 FK（被举报内容删除后 LEFT JOIN 不到即显示为已删除）。旧库补列由 DatabaseMigrator `ensureColumn("reports", ...)` 完成。
- 接口：`POST /posts/{id}/report`、`POST /replies/{id}/report`（需登录，404 `帖子不存在。`/`评论不存在。`，返回 `{reported:true}` 幂等）；`GET /api/reports`（需登录 + 管理员 admin_level≥1，403 `需要管理员权限。`，返回嵌套结构：`{id,target_type,target_id,reason,created_at,status,handled_at,result,reporter:{id,username},post:{id,title}|null,reply:{id,content,post_id}|null}`）；`POST /api/reports/{id}/handle`（需登录 + 管理员，body `{action}`：`delete`=删目标内容+警告作者（作者 user_id 为空则不警告），`ban`=删目标内容+封禁作者（body 加 `ban_days:'7'` 1~3650 天或 `ban_permanent:'true'` 永久；**不发** warning），`ignore`=仅置忽略；`@Transactional`；404 `举报不存在。`/400 `无效的操作。`/`请指定封禁时长（1~3650 天）或选择永久封禁。`；返回 `{ok,status,result}`）。⚠️ 重复处理未阻止（幂等，目标已删时 result 变「内容已删除」）。删除逻辑抽成 `deleteTargetContent(row)` 返回作者 id（delete/ban 共用）。
- ⚠️ **删除场景的通知 post_id/reply_id 必须传 NULL**：通知表 FK `fk_notifications_post/reply ... ON DELETE CASCADE` 会拒绝插入引用已删内容的通知（先删目标再插通知会 FK 报错，整个 @Transactional 回滚）。
- 通知：处理成功通知举报人 type=`report`（title「举报受理结果」，content「您在 {举报时间} 提交的举报已受理，结果为：{result}。」，时间格式 yyyy-MM-dd HH:mm）；`delete` 额外向作者发 type=`warning`（title「帖子《xx》」/「你发布的评论」）。ignore 不警告作者。
- ⚠️ 接口契约是**嵌套对象**（reporter/post/reply），后端 `ReportController.toNestedList` 把扁平 DAO 行转嵌套 Map 输出（HashMap 允许 null value）。
- 前端：PostView 帖子/评论「举报」按钮（自己的内容不显示）+ 共享举报 dialog（原因可选 ≤200 字）；App.vue 顶部齿轮左侧红色「举报受理」按钮（仅 admin_level>0）→ `/admin/reports`（AdminReportsView：待处理显示「删除并封禁」「删除并警告」「忽略」按钮 + 确认，已处理显示「已处理/已忽略」标签；点「查看原帖」跳 `/post/{post_id}`，目标已删则按钮禁用显示「目标已删除」）；MessagesView 通知项 `report`/`warning` 类型渲染标题。

## 封禁（已完成）
- `users.ban_until DATETIME NULL`（schema.sql + `DatabaseMigrator.ensureColumn` 补列）：NULL=未封禁；晚于当前时间=封禁中；`2099-12-31 23:59:59`=永久封禁；过期自动视为解封（拦截器用 `isAfter(now)` 判断，无需定时任务）。
- `User` record / `UserProfile` 加 `banUntil` 字段（`@JsonInclude(NON_NULL)`，未封禁不返回）；`UserDao.updateBanUntil(id, until)`（null=解封）。
- ⚠️ 改 `User` record 构造后，所有 `new User(...)`/查 User 的 DAO 都要同步补列：`UserDao` 3 处 SELECT、`FollowDao` 的 USER_ROW_MAPPER + 2 处 SELECT 已加 `ban_until`（漏改会编译报错）。
- **拦截器封禁校验**（`AuthInterceptor` 注入 UserDao）：token 校验后查用户，封禁中返回 403 `您已被封禁。封禁至 yyyy-MM-dd HH:mm。`/`您已被永久封禁。`；**唯一放行 `/api/auth/me`**（前端靠它刷新封禁状态→锁屏）。`/api/auth/login` 不在拦截器（封禁用户也能登录，登录响应带 `ban_until`）。
- **解封**：`POST /api/users/{id}/unban`（需登录 + 管理员，WebConfig 注册 `/api/users/*/unban`）清除 ban_until。
- 前端：App.vue 全屏锁屏（`user.ban_until` 未过期 → 大字「您已被封禁」+ 封禁时间/永久 + 退出登录按钮；`refreshUser()` 调 `/auth/me` 刷新用户）；AdminReportsView「删除并封禁」→ el-dialog 选 1 天/7 天/30 天/永久 → `{action:'ban', ban_days|ban_permanent}`；ProfileView 显示「该用户已被封禁」标签 + 管理员「解封」按钮（`/users/{id}/unban` 后 reload）。

## 社交·搜索用户（已完成）
- 左侧边栏「社交」组第 1 项「搜索用户」→ `/search-user`（HomeView `leftNavGroups` 第 4 组 label 改为「社交」，4-1 用 `to: '/search-user'` 走 `onLeftNavChildClick` 的 `child.to` 分支；4-2~4-4 占位保留）。
- 后端 `GET /api/users/search?q=`（公开，无需登录，不在拦截器路径）：`UserDao.findByNameLike` 用 `username LIKE '%kw%' ORDER BY id DESC LIMIT 50`（用户名只允许中英文/数字/下划线，无需 LIKE 转义）。⚠️ 与 `GET /api/users/{id}` 共存：Spring 精确路径 `/search` 优先于 `/{id}` 模板，不会冲突。
- 前端 `SearchUserView.vue`（`/search-user`，懒加载注册到 router）：搜索框 + 结果卡片列表（头像/用户名/签名，管理员徽章、封禁「已封禁」标签），点击进 `/user/{id}`。

## 修改帖子分区（已完成）
- 分区 = `posts.category` 自由字符串，来源前端 `utils/format.js` 的 `categories` 常量（`['话题','galgame','全站动态','gal情报','gal资源','资源和求助','其他']`）；分区与标签（`post_tags` section_key）相互独立。
- 后端 `PUT /api/posts/{id}/category`（需登录 + admin_level≥1，403 `需要管理员权限。` / 404 `帖子不存在。` / body `{category}` trim 非空且 ≤32 字符否则 400）：`PostDao.updateCategory(postId, category)` 直接 `UPDATE posts.category`，返回 `{ok, category}`。WebConfig 已注册 `/api/posts/*/category`。后端不做分区白名单（集合由前端常量定义，改前端常量即生效）。
- 前端：PostView `isAdmin`（`user.admin_level > 0`）显示「修改分区」按钮 → el-dialog 下拉 `categories`（默认当前 `post.category`）→ `api.put` 成功后更新 `post.category` 显示。

## 公告（已完成）
- 左侧边栏「发布」组第 2 个子项改为「发布公告」（HomeView `leftNavGroups`，`action:'announcement'` 走 `onLeftNavChildClick` 自定义点击）；普通用户（`user.admin_level` 非 >0）点击 `ElMessage.warning('请通过1级管理员认证。')`；1 级管理员弹 dialog 输入标题+正文，POST `/api/announcements`。
- ⚠️ **Spring Boot 4 方法级绝对路径不会覆盖类级前缀**：曾把 `POST /api/announcements` 放在 `NotificationController`（类级 `/api/notifications`）用方法级 `/api/announcements`，实际 404。必须新建独立 `AnnouncementController`（类级 `@RequestMapping("/api")` + 方法级 `/announcements`）。
- `POST /api/announcements`（**multipart/form-data**，需登录 + admin_level≥1，403 `请通过1级管理员认证。`，标题/正文 trim 为空 400，`@Transactional`）：`UserDao.findAllUserIds()` 取全部用户 id，循环 `NotificationDao.insert(uid, "announcement", adminId, null, null, title, content, mediaJson)` 广播，返回 `{ok, count, media}`。WebConfig 已注册 `/api/announcements`。
- **媒体附件**：`AnnouncementController` 用 `consumes = MULTIPART_FORM_DATA_VALUE`（前端必须 FormData，即使无文件也要 multipart——requests 库 `files={}` 空 dict 会退化成 urlencoded → 415，e2e 用空字节文件强制 multipart）。可选参数 `attachments`（`MultipartFile[]`）：mime 白名单仅 image/audio/video 前缀（否则 400 `仅支持图片、音频、视频附件。`），总大小 ≤250MB（400 `附件总大小超出限制。`）；文件存 `uploads/announcements/`，文件名 `{时间戳}_{nowToken}_{UUID无横线}_{净化名}`（`sanitizeFilename` 取 basename + 非法字符替换 + 截断 100），url 为 `/uploads/announcements/<storedName>`。媒体列表经 `tools.jackson.databind.ObjectMapper` 序列化成 JSON 写入 notifications.media（Jackson 3 抛运行时异常，无需 try-catch；空列表传 null）。`NotificationDao` 新增 8 参数 `insert(...media)` 重载；`Notification` record 加 `media` 字段（`@JsonInclude(NON_NULL)`）；`GET /api/notifications` 每条返回 `media`。
- **前端媒体渲染**：HomeView 公告 dialog 加 `<input type="file" multiple accept="image/*,audio/*,video/*">`（`annFiles` ref + `onAnnFileChange`/`removeAnnFile`），`submitAnnouncement` 用 FormData append title/content/attachments（`h_headers` 不能手动设 Content-Type）；MessagesView `.notif-media` 区按 mime 前缀渲染 `<img>`（240px）/ `<audio controls>` / `<video controls>`（320px），解析函数 `notifMedia(n)` 从 `n.media` JSON 分组 image/audio/video。
