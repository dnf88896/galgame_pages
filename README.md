# KUN Gal Forum

基于 **Vue 3 + Element Plus / Spring Boot + JDBC / MySQL** 的 Galgame 论坛（仿 kungal.com）。

## 功能

- **账号系统**：注册 / 登录 / 退出，用户名+密码，BCrypt 加密，登录后自动登录；Bearer Token（随机 32 字节 hex，服务端存 SHA-256，30 天有效）
- **个人资料**：`/user/:id` 展示头像、用户名、个性签名、注册时间与统计（发帖数/回复数/获赞数）及最近帖子；可编辑签名、上传头像（仅本人）
- **帖子**：列表（时间倒序）、详情、发帖；可选分区、可选附件上传；作者自动关联账号
- **标签系统**（仿 kungal）：三大类（`galgame` / `technique` / `others`）+ 27 个小分支标签（`g-*`/`t-*`/`o-*` 前缀区分所属大类）。最左侧导航「话题」按钮悬浮出 4 个分支（全部话题 / Galgame / 技术交流 / 其它话题）；`/tag/:category` 大类页（描述 + 小分支网格），`/tag/:category/:section` 小分支页（该标签下帖子列表）。**分区（板块）与标签相互独立**：发帖页「分区」选板块（话题/galgame/全站动态/…），「标签（可选）」可**多选** 27 个小分支（存 `post_tags` 多对多关联表）；帖子卡片显示板块名 + 多个 `#标签`。标签名与描述取自 kungal 开源仓库；`GET /api/tags` 附带各标签实时帖子数（大类计数 = 其下小分支计数之和）
- **回复**：平铺列表（时间正序）、发表回复；回复数实时计数；作者自动关联账号；**可回复某条评论**（嵌套回复，回复也算该帖评论，父回复显示「回复 @作者」引用，`parent_id` 自引用 + `parent_author` 作者名快照——**父评论被删除后子回复仍保持「回复 @作者」引用，不孤儿化**）；**可删除自己的回复**（含嵌套；删父回复时子回复保留、`parent_id` 自动置空但 `parent_author` 快照保留，回复数同步 -1，相关 @ 通知级联清除）
- **浏览计数**：每次打开详情页 +1
- **点赞**：帖子和回复均可赞，**再次点击可取消**；**需登录**，同一用户对同一目标去重
- **搜索**：按 标题/正文/作者/分区 模糊匹配
- **关注**：在他人主页一键关注/取关，主页显示 关注/粉丝 数与列表（`/user/:id/following`、`/user/:id/followers`）
- **私聊**：他人主页「私聊」入口；顶栏「消息」按钮（带未读红点）查看会话列表并可回复，聊天页 4 秒轮询
- **@提及 + 消息通知**：发帖/回复正文 `@用户名` 会通知被 @ 的用户（自动解析，同一帖重复 @ 只记一条，@ 自己不通知）；顶栏「消息」里「私信 / 通知」双页签，通知页显示「谁 @ 了我 + 相关帖子入口」，进入即标已读，未读数并入顶栏红点；通知表 `type` 字段预留 **announcement 公告**（`title`/`content` 承载正文，前端按 type 通用渲染）
- **屏蔽**：主页「屏蔽/已屏蔽」入口 toggle；屏蔽后双方互不可见对方帖子（列表/详情 404/最近发布隐藏），私聊页显示「你已屏蔽对方 / 对方已屏蔽你」（互屏蔽优先显示前者）且无法发送
- **附件**：multipart 上传，存本地 `backend/uploads/post_<id>/`，列表/详情内嵌预览（图/音/视频/文件）
- 发帖/回复/点赞均需登录，作者由登录账号自动填充（旧数据保留原始作者文本，不迁移）

## 技术栈

| 层 | 技术 |
|---|---|
| 前端 | Vue 3 + Vite + Element Plus + vue-router + axios |
| 后端 | Spring Boot 4.1 + Maven + JDBC（JdbcTemplate）+ spring-security-crypto（BCrypt） |
| 数据库 | MySQL 8.4（库名 `galgame`，utf8mb4） |

## 环境要求

- **Node.js ≥ 20**（本机 v24.16，已就绪）
- **JDK 17+**（本机 Maven 使用 `JAVA_HOME` = JDK 17）
- **Maven 3.9**（便携版装在 `C:\Users\yinsh\dev-tools\apache-maven-3.9.16`，已加入用户 PATH）
- **MySQL 8.4**（本机装在 `C:\Users\yinsh\dev-tools\mysql`）。**路径任意**——`start-forum.bat` 会自动探测 mysqld 位置（缓存 `mysql-config.txt` → 环境变量 `MYSQL_HOME` → PATH → 常见安装目录），探测不到时第一次启动会让你手动输入一次 `bin` 目录并缓存

> 工具（Node/JDK/Maven/MySQL 程序）都装在项目文件夹之外；**数据库数据在项目内**（`database/`），随项目走。

## 数据库：MySQL 8.4（便携版）

- MySQL 程序：`C:\Users\yinsh\dev-tools\mysql`
- **数据目录（项目内）：`database/mysql-data`**
- **配置文件（项目内）：`database/my.ini`**（basedir 指向 dev-tools）
- **完全跟随项目位置**：`start-forum.bat` 启动 MySQL 时用命令行覆盖（优先级高于 my.ini）：
  - `--basedir=` 探测到的 MySQL 安装目录（无需改 my.ini）
  - `--datadir="%~dp0database\mysql-data"` 项目内数据目录
  - `--port=` 自动选择（见下）
  因此**整个项目文件夹可整体拷贝/移动，数据跟着走**。my.ini 里的路径仅作「不带参数手动启动」的备用。
- **端口自适应**：默认 3306；若 3306 已被占用，自动改试 **3307~3310**，并把实际端口通过环境变量 `DB_PORT` 传给后端。后端 `application.properties` 已支持 `${DB_PORT:3306}` / `${DB_USER:galgame}` / `${DB_PASS:galgame123}` 覆盖。
- 手动启动（本机非管理员，用后台进程方式，**同样需要带 `--datadir`**，端口按需加 `--port`）：

  ```bash
  C:/Users/yinsh/dev-tools/mysql/bin/mysqld --defaults-file="C:/Users/yinsh/Desktop/claude code/galgame_pages/database/my.ini" --datadir="C:/Users/yinsh/Desktop/claude code/galgame_pages/database/mysql-data"
  ```

- 连接信息：

  | 用途 | 账号 | 密码 | 库 |
  |---|---|---|---|
  | 应用 | `galgame` | `galgame123` | `galgame` |
  | 管理员 | `root` | `root` | — |

- 表结构：后端启动时自动执行 `backend/src/main/resources/schema.sql`（`CREATE TABLE IF NOT EXISTS`，幂等），共 13 张表：`users`、`auth_tokens`、`posts`、`post_tags`、`replies`、`post_likes`、`reply_likes`、`attachments`、`follows`、`dm_conversations`、`dm_messages`、`user_blocks`、`notifications`。
  - 新增表：`users`（账号/密码哈希/资料）、`auth_tokens`（token SHA-256 + 用户 + 过期时间）、`post_tags`（帖子-标签多对多，`(post_id, section_key)` 联合主键）、`notifications`（消息通知：`type` 区分 mention/announcement，`title`/`content` 承载正文，公告预留）
  - 变更表：`posts`/`replies` 增加 `user_id`；`posts` 的 `section` 单值列迁移到 `post_tags`（DatabaseMigrator 启动时自动迁移旧数据并删除该列）；`replies` 增加 `parent_id`（嵌套回复自引用，`ON DELETE SET NULL`）；`post_likes`/`reply_likes` 主键由访客 ID 改为 `(post_id, user_id)` / `(reply_id, user_id)`；旧数据的 `user_id` 为 NULL、作者名保留原样

## 启动步骤

> **一键启动：** 双击项目根目录的 **`start-forum.bat`**，会自动拉起 MySQL/后端/前端并打开浏览器；要停止服务双击 **`stop-forum.bat`**。（脚本已做「已在运行则跳过」判断，重复双击不会起重复进程）
>
> 首次在**新机器**上运行：脚本自动探测 MySQL，找不到会问一次 `mysqld.exe` 所在目录（例如 `C:\mysql\bin`），输入后缓存到 `mysql-config.txt`，以后不再问。

手动启动方式（等价）：

1. **启动 MySQL**（见上）
2. **启动后端**（端口 8080）：

   ```bash
   cd backend
   mvn spring-boot:run
   # 或直接跑已构建的 jar：java -jar target/galgame-backend-0.0.1-SNAPSHOT.jar
   ```

3. **启动前端**（端口 5173）：

   ```bash
   cd frontend
   npm install
   npm run dev
   ```

4. 浏览器打开 <http://localhost:5173>

## 拷到别的机器（给别人测试）

把整个 `galgame_pages` 文件夹复制过去即可，目标机器需要装好：

| 依赖 | 要求 |
|---|---|
| Java | JDK 17+，`java` 在 PATH 上 |
| Node.js | ≥ 20，`npm` 可用 |
| MySQL | 8.4.x，**装在哪个路径都行**（脚本自动找，找不到会问一次） |

要点：

- 后端已打包的 jar（`backend/target/`）随包走，目标机器**无需 Maven**（只有 jar 缺失时才回退 `mvn spring-boot:run`）
- `frontend/node_modules` 一并拷贝，目标机器**离线**也能直接 `npm run dev`，无需联网装依赖
- 数据库数据（`database/mysql-data`）随包走，帖子/用户/账号密码都在里面，**无需在目标机器建库建账号**
- 目标机器 3306 已被自己的 MySQL 占用时，脚本自动换到 3307~3310，不影响对方
- 目标机器 MySQL 版本建议同为 8.4（8.0 / 9.x 可能读不了 8.4 建的数据目录）
- `stop-forum.bat` 会停止 3306~3310 / 8080 / 5173 上的进程

## 目录结构

```
galgame_pages/
├── database/                 MySQL 数据目录（项目内）+ my.ini
│   ├── my.ini                数据库配置（basedir/datadir/port）
│   └── mysql-data/           实际数据文件（运行时生成）
├── frontend/                 Vue 3 + Vite + Element Plus
│   └── src/
│       ├── main.js           入口（注册 Element Plus、router）
│       ├── router/           路由（含 /login、/user/:id）
│       ├── api/              axios 实例（baseURL 指向后端 8080，自动带 Bearer Token）
│       ├── store/user.js     登录状态单例（localStorage 存 galforum_token / galforum_user）
│       ├── store/unread.js   顶栏「消息」未读刷新信号（通知标已读后触发红点重拉）
│       ├── utils/format.js   分区常量/时间/文件大小/附件 URL/标签结构 等工具
│       ├── components/AttachmentList.vue   附件预览组件
│       └── views/            HomeView（列表+搜索+分区栏）、ComposeView（发帖+标签选择）、PostView（详情+回复+点赞）、SectionView（`/tag/:category` 大类页 + `/tag/:category/:section` 小分支页）、AuthView（登录/注册）、ProfileView（个人资料+关注/私聊/屏蔽）、FollowListView（关注/粉丝列表）、MessagesView（私信列表）、ConversationView（私聊窗口）
├── backend/                  Spring Boot + JDBC
│   ├── src/main/java/com/galgame/
│   │   ├── auth/              TokenService / AuthContext / AuthInterceptor（Bearer 校验）
│   │   ├── config/            WebConfig（CORS + /uploads 静态映射 + 认证拦截器）、SecurityConfig（BCrypt）、DatabaseMigrator（幂等建表）
│   │   ├── constants/         TagConstants（三大类 + 27 小分支，kungal 原样）
│   │   ├── controller/        PostController / ReplyController / AuthController / UserController / FollowController / DmController / BlockController / TagController / NotificationController
│   │   ├── dao/               PostDao / ReplyDao / AttachmentDao / UserDao / AuthTokenDao / FollowDao / DmDao / BlockDao / NotificationDao
│   │   ├── service/           MentionService（@用户名解析 + 通知写入，失败静默不影响主流程）
│   │   └── model/             Post / Reply / Attachment / LikeResult / User / UserProfile / RecentPost / DmMessage / PostFilter / Notification
│   ├── src/main/resources/
│   │   ├── application.properties      数据源 + multipart 250MB + JSON snake_case
│   │   └── schema.sql                  11 张表建表脚本（自动执行）
│   ├── uploads/             附件文件存储（post_<id>/）+ 头像（avatars/）
│   └── pom.xml
├── research/                 kungal.com 开源仓库参考（kun-galgame-forum + kun-galgame-infra，只读参考）
├── start-forum.bat / stop-forum.bat    一键启停脚本
├── mysql-config.txt          MySQL 探测缓存（首次运行自动生成/更新，可删，脚本会自动重建）
└── README.md
```

## API（Base `http://localhost:8080/api`）

JSON 字段一律 snake_case（`created_at`、`reply_count`…），错误响应 `{"error":"..."}`。🔒 = 需登录（`Authorization: Bearer <token>`），未登录返回 401。

**账号 / 资料**

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/auth/register` | 注册（`{username, password}`），成功即返回 token 自动登录 |
| POST | `/api/auth/login` | 登录（`{username, password}`），返回 `{token, user}` |
| POST | `/api/auth/logout` | 🔒 退出（吊销当前 token） |
| GET | `/api/auth/me` | 🔒 当前登录用户信息 |
| PUT | `/api/auth/profile` | 🔒 修改资料（`{bio}`） |
| POST | `/api/auth/avatar` | 🔒 上传头像（multipart 文件字段 `file`） |
| GET | `/api/users/{id}` | 用户公开资料（含统计 + 最近帖子 + `follower_count`/`following_count`/`is_following`/`is_blocked`；双方任一方向存在屏蔽时 `recent_posts` 为空） |

**帖子 / 回复 / 点赞**

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/posts?q=&category=&section=` | 帖子列表（倒序），可选 q 关键词搜索 / category 分区 / section 标签过滤；含 `attachments[]` |
| GET | `/api/tags` | 标签树 `{categories:[{key,label,description,post_count,sections:[{key,label,post_count}]}]}`，三大类 + 27 小分支 + 实时计数（公开） |
| GET | `/api/boards` | 各分区帖子数 `[{name, count}]`，供首页左侧分区栏展示 |
| GET | `/api/posts/{id}` | 帖子详情（浏览数 +1）；含 `liked`、`replies[]`（正序，各带 `liked`） |
| POST | `/api/posts` | 🔒 发帖（JSON 或 multipart，字段 category/sections/title/content + attachments 文件；作者自动取登录账号）。**分区（category）与标签（tags）相互独立**：category 存板块，sections 存多个标签 key（可多选，multipart 重复参数 / JSON 数组，兼容单值 section）；非法标签返回 400 `无效的标签。`；帖子 JSON 的标签字段为 `tags` 数组 |
| DELETE | `/api/posts/{id}` | 🔒 删除自己的帖子（作者本人；级联删除回复/点赞/附件记录并清理附件文件） |
| POST | `/api/posts/{id}/replies` | 🔒 发回复（JSON `{content, parent_id?}` 或 multipart content/parent_id；`parent_id` 可选，指定后为嵌套回复，父回复必须存在且属于该帖否则 400 `父回复不存在。`；作者自动取登录账号） |
| POST | `/api/posts/{id}/like` | 🔒 帖子点赞/取消，返回 `{liked, like_count}` |
| POST | `/api/replies/{id}/like` | 🔒 回复点赞/取消（同上） |
| DELETE | `/api/replies/{id}` | 🔒 删除自己的回复（作者本人；子回复保留、`parent_id` 自动置 NULL 但 `parent_author` 快照保留，前端仍显示「回复 @作者」；`reply_count` -1，相关 @ 通知级联删除） |
| GET | `/uploads/**` | 附件/头像静态文件 |

**关注**

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/users/{id}/follow` | 🔒 关注/取关 toggle，返回 `{following, follower_count}`（不能关注自己） |
| GET | `/api/users/{id}/following` | 该用户关注的人 `[{id, username, avatar_url, bio, is_following}]` |
| GET | `/api/users/{id}/followers` | 关注该用户的人（同上 shape；`is_following` 需登录判断） |

**私聊（DM）**

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/dm/conversations` | 🔒 会话列表 `[{user, last_message, unread}]`，按最后消息倒序 |
| GET | `/api/dm/conversations/{userId}` | 🔒 取/建会话并返回全部消息 + `blocked_by_me`/`blocked_by_them`，返回后把对方发来的未读标为已读 |
| POST | `/api/dm/conversations/{userId}/messages` | 🔒 发消息 `{content}`，返回新消息（≤2000 字）；任一方已屏蔽对方时返回 403 |

**屏蔽**

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/users/{id}/block` | 🔒 屏蔽/解除屏蔽 toggle，返回 `{blocked}`（不能屏蔽自己）；屏蔽后双方互不可见对方帖子，私聊显示屏蔽状态且禁止发送 |

**消息通知**

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/notifications` | 🔒 通知列表（新→旧，≤50），每条 `{id, type, actor:{id,username,avatar_url,...}|null, post_id, reply_id, title, content, is_read, created_at}`；`type`=mention（被 @）/ announcement（公告，预留） |
| POST | `/api/notifications/read` | 🔒 全部标已读，返回 `{read:n}` |
| GET | `/api/notifications/unread-count` | 🔒 未读数 `{unread:n}`，并入顶栏「消息」红点 |

校验规则：用户名 2-20 字符（中文/字母/数字/下划线）、密码 ≥6；标题/正文/回复内容不能为空；标题 ≤80、正文/回复 ≤2000；附件总大小 ≤250MB。

## 登录与 Token 机制

- **注册**：用户名唯一（冲突返回 409），密码用 BCrypt 加密存 `users.password_hash`；注册成功即登录。
- **登录**：校验密码后生成 token——`SecureRandom` 生成 32 字节十六进制串发给前端，服务端只存其 SHA-256（`auth_tokens.token_hash`），有效期 30 天，不存明文，防数据库泄露。
- **请求认证**：axios 请求拦截器自动加 `Authorization: Bearer <token>`（前端存 `localStorage` 的 `galforum_token`）；后端 `AuthInterceptor` 校验 token 并写入 `AuthContext`，发帖/回复/点赞自动关联 `user_id`。
- **401 处理**：响应拦截器收到 401 清除本地登录态并跳转 `/login`（带 `?redirect=` 原路返回）。
- 登录后作者名显示为账号用户名，可点击进入个人资料页。
