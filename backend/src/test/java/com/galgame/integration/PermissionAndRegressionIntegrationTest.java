package com.galgame.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import tools.jackson.databind.JsonNode;

/**
 * 权限边界 + 历史 bug 回归集成测试。
 *
 * <p><b>两类目标</b>：
 * <ol>
 *   <li><b>权限边界</b>：未登录 401 / 非本人 403 / 非管理员 403，覆盖帖子评论与 Galgame 评论两条链路。
 *       ⚠️ 两条链路的鉴权实现不同——{@code /api/replies/**} 走 AuthInterceptor 拦截器，
 *       {@code /api/galgame-replies/**} 未注册拦截器、Controller 手动 {@code tokenService.resolveUserId}，
 *       所以两边都要各测一次，不能只测一条就认为覆盖了。</li>
 *   <li><b>历史 bug 回归</b>：每个带「历史 bug」字样的方法对应一次真实踩过的坑，
 *       方法注释写明防的是什么，改坏时会立刻红。</li>
 * </ol>
 *
 * <p><b>请求格式（读源码确认，非猜测）</b>：
 * <ul>
 *   <li>发帖：{@code POST /api/posts}，Content-Type {@code application/json}，body {@code {title, content, category?, sections?}}，
 *       成功 201（PostController 的 createJson 分支；另有 multipart 分支，测试不需要）。</li>
 *   <li>帖子评论：{@code POST /api/posts/{id}/replies}，{@code application/json}，body {@code {content, parent_id?, images?}}，成功 201。</li>
 *   <li>Galgame 评论：{@code POST /api/galgames/{id}/replies}，{@code application/json}，body {@code {content, is_long?, parent_id?}}，成功 201；
 *       仅 status=approved 的条目可评论，故测试里先用管理员建一条（管理员创建即 approved）。</li>
 * </ul>
 *
 * <p><b>不依赖残留数据</b>：所有账号用 {@link #uniqueName(String)} 生成；
 * 计数类断言只针对本次新建的帖子/评论（全新对象，计数从 0 起）；列表类断言只断言「包含」不断言总数。
 */
@DisplayName("权限边界 + 历史 bug 回归")
class PermissionAndRegressionIntegrationTest extends IntegrationTestBase {

    // ── A. 通用权限边界 ─────────────────────────────

    /**
     * 未登录调用任何需要登录的写接口都必须 401，且提示统一为「请先登录。」。
     * <p>防的坑：新增写接口时忘了把路径注册进 AuthInterceptor（或忘了手动鉴权），
     * 导致匿名用户能发帖 / 刷赞 / 删评论。这里把 6 条写路径一次性钉住。
     */
    @Test
    void 未登录调用发帖评论点赞等写接口一律返回401() throws Exception {
        Actor actor = registerActor("anon");
        long postId = createPost(actor, "匿名调用测试帖", "正文");

        // 发帖（POST /api/posts 同时承载公开的 GET 列表，拦截器只拦 POST）
        MvcResult create = mockMvc.perform(post("/api/posts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(map("title", "匿名发帖", "content", "正文"))))
                .andExpect(status().isUnauthorized())
                .andReturn();
        assertThat(body(create).path("error").asString()).isEqualTo("请先登录。");

        // 评论帖子
        mockMvc.perform(post("/api/posts/" + postId + "/replies")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(map("content", "匿名评论"))))
                .andExpect(status().isUnauthorized());

        // 帖子点赞
        mockMvc.perform(post("/api/posts/" + postId + "/like"))
                .andExpect(status().isUnauthorized());

        // 删除帖子评论（拦截器覆盖 /api/replies/*）
        mockMvc.perform(delete("/api/replies/" + postId))
                .andExpect(status().isUnauthorized());

        // Galgame 评论发帖 / 点赞 / 删除：该路径未注册拦截器，靠 Controller 手动鉴权。
        // 鉴权判断在「条目是否存在」之前，故用不存在的 galgame id 也必须是 401 而不是 404。
        mockMvc.perform(post("/api/galgames/999999999/replies")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(map("content", "匿名评论"))))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/galgame-replies/" + postId + "/like"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/galgame-replies/" + postId + "/dislike"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(delete("/api/galgame-replies/" + postId))
                .andExpect(status().isUnauthorized());
    }

    /**
     * 帖子评论：非作者删除别人的评论必须 403，且评论仍在。
     * <p>防的坑：删除接口只校验了「登录」没校验「本人」（横向越权），任何人都能删别人的评论。
     */
    @Test
    void 非作者删除他人帖子评论返回403且评论仍在() throws Exception {
        Actor author = registerActor("ra");
        Actor other = registerActor("rb");
        long postId = createPost(author, "越权删除测试帖", "正文");
        long replyId = createPostReply(postId, author, "作者的评论");

        MvcResult result = mockMvc.perform(delete("/api/replies/" + replyId)
                        .header("Authorization", other.bearer()))
                .andExpect(status().isForbidden())
                .andReturn();
        assertThat(body(result).path("error").asString()).isEqualTo("只能删除自己的回复。");

        assertThat(findById(postDetail(postId, other).path("replies"), replyId))
                .as("越权删除被拒后评论必须还在")
                .isNotNull();
    }

    /**
     * Galgame 评论：非作者删除别人的评论必须 403，且评论仍在。
     * <p>与帖子评论各测一条：两条链路的鉴权实现不同（拦截器 vs Controller 手动解析 token），
     * 只测一条不能证明另一条也是安全的。
     */
    @Test
    void 非作者删除他人galgame评论返回403且评论仍在() throws Exception {
        Actor admin = registerAdmin("ga");
        long galgameId = createApprovedGalgame(admin);
        Actor author = registerActor("gc");
        Actor other = registerActor("gd");
        long replyId = createGalgameReply(galgameId, author, "作者自己发的评论");

        MvcResult result = mockMvc.perform(delete("/api/galgame-replies/" + replyId)
                        .header("Authorization", other.bearer()))
                .andExpect(status().isForbidden())
                .andReturn();
        assertThat(body(result).path("error").asString()).isEqualTo("只能删除自己的回复。");

        assertThat(findById(galgameDetail(galgameId, other).path("replies"), replyId))
                .as("越权删除被拒后评论必须还在")
                .isNotNull();
    }

    /**
     * 管理员专属接口（GET /api/galgames/pending）：普通用户 403、未登录 401、管理员 200。
     * <p>防的坑：审核类接口只判了「有没有登录」没判「是不是管理员」，普通用户能看待审列表甚至审核。
     * <p>第三个断言（管理员 200）是必要的反面证据——否则把接口改成「一律 403」也能让前两个断言通过。
     */
    @Test
    void 普通用户访问管理员专属接口返回403而管理员可访问() throws Exception {
        Actor normal = registerActor("usr");

        MvcResult forbidden = mockMvc.perform(get("/api/galgames/pending")
                        .header("Authorization", normal.bearer()))
                .andExpect(status().isForbidden())
                .andReturn();
        assertThat(body(forbidden).path("error").asString()).isEqualTo("需要管理员权限。");

        mockMvc.perform(get("/api/galgames/pending"))
                .andExpect(status().isUnauthorized());

        Actor admin = registerAdmin("adm");
        mockMvc.perform(get("/api/galgames/pending").header("Authorization", admin.bearer()))
                .andExpect(status().isOk());
    }

    // ── B. 历史 bug 回归 ────────────────────────────

    /**
     * 作者删除自己发的帖子评论必须成功。
     * <p>实现：ReplyController 用基本类型比较（{@code long != long}），本身没踩坑，
     * 但作为 Galgame 版（下一个方法）的对照组保留——两条链路行为必须一致。
     */
    @Test
    void 作者删除自己发的帖子评论成功() throws Exception {
        Actor actor = registerActorUncached("pa");
        long postId = createPost(actor, "作者删帖评论测试帖", "正文");
        long replyId = createPostReply(postId, actor, "自己的评论");
        assertThat(postDetail(postId, actor).path("reply_count").asInt()).isEqualTo(1);

        MvcResult result = mockMvc.perform(delete("/api/replies/" + replyId)
                        .header("Authorization", actor.bearer()))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(body(result).path("ok").asBoolean()).isTrue();
        assertThat(findById(postDetail(postId, actor).path("replies"), replyId)).isNull();
        assertThat(postDetail(postId, actor).path("reply_count").asInt())
                .as("删评论后帖子的回复数应 -1")
                .isZero();
    }

    /**
     * <b>历史 bug 回归</b>：作者删除自己发的 Galgame 评论必须成功（原本被误判 403）。
     * <p><b>防的坑</b>：GalgameReplyController.delete 里写成 {@code reply.userId() != uid.get()}——
     * 两边都是 <b>Long 包装类</b>，{@code !=} 是<b>引用比较</b>而非值比较，于是恒判不等，
     * 作者删自己的评论也返回 403「只能删除自己的回复。」。修复为 {@code !reply.userId().equals(uid.get())}。
     * <p><b>为什么要大 userId</b>：{@code Long.valueOf} 缓存 -128~127，只有超出该区间的 id 才会箱出不同对象，
     * 引用比较的 bug 才会暴露（小 id 会「假性通过」）。本方法显式把 users 的自增值推到 100000
     * 再注册用户，保证无论测试库是新是旧都能真实触发这个分支。
     * <p>断言取 {@code 200 {ok:true}}（源码实现是 200，不是 204）。
     */
    @Test
    void 作者删除自己发的galgame评论成功_防Long引用比较回归() throws Exception {
        Actor admin = registerAdmin("ha");
        long galgameId = createApprovedGalgame(admin);
        Actor author = registerActorUncached("hb");
        long replyId = createGalgameReply(galgameId, author, "作者自己发的评论");

        MvcResult result = mockMvc.perform(delete("/api/galgame-replies/" + replyId)
                        .header("Authorization", author.bearer()))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(body(result).path("ok").asBoolean()).isTrue();
        assertThat(findById(galgameDetail(galgameId, author).path("replies"), replyId))
                .as("作者删自己的评论必须真的删掉")
                .isNull();
    }

    /**
     * <b>历史 bug 回归</b>：{@code GET /api/users/{id}/following} 与 {@code /followers} 必须返回 200 且带昵称。
     * <p><b>防的坑</b>：FollowDao 两条 SQL 由多段字符串拼接，{@code "u.created_at"} 与 {@code "FROM"} 之间
     * 漏了一个空格，拼成 {@code created_atFROM}——任何一次调用都是 500（SQL 语法错误），
     * 且 @ 自动补全 / 关注页双双瘫痪。修复是补上那个空格；本测试只要再漏一次就会红。
     * <p>昵称断言的必要性：FollowController.toFollowResponse 曾经只放了 username，
     * 关注列表拿不到 nickname 就没法显示昵称，故这里用「改过昵称的账号」断言 nickname 字段真的返回。
     */
    @Test
    void 关注与粉丝列表必须返回200且带昵称_防SQL缺空格回归() throws Exception {
        Actor a = registerActor("fa");
        Actor b = registerActor("fb");
        setNickname(a, "回归测试甲");
        setNickname(b, "回归测试乙");

        MvcResult follow = mockMvc.perform(post("/api/users/" + b.id() + "/follow")
                        .header("Authorization", a.bearer()))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(body(follow).path("following").asBoolean()).isTrue();

        // A 关注的人里应包含 B，且带 B 的昵称（SQL 缺空格时这里是 500 而不是 200）
        MvcResult following = mockMvc.perform(get("/api/users/" + a.id() + "/following")
                        .header("Authorization", a.bearer()))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode bNode = findById(body(following), b.id());
        assertThat(bNode).as("A 的关注列表应包含 B").isNotNull();
        assertThat(bNode.path("nickname").asString()).isEqualTo("回归测试乙");
        assertThat(bNode.path("username").asString()).isEqualTo(b.username());
        assertThat(bNode.path("is_following").asBoolean())
                .as("带登录态时 is_following 应为 true（才关注过）")
                .isTrue();

        // B 的粉丝列表里应包含 A，且带 A 的昵称
        MvcResult followers = mockMvc.perform(get("/api/users/" + b.id() + "/followers")
                        .header("Authorization", a.bearer()))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode aNode = findById(body(followers), a.id());
        assertThat(aNode).as("B 的粉丝列表应包含 A").isNotNull();
        assertThat(aNode.path("nickname").asString()).isEqualTo("回归测试甲");
        assertThat(aNode.path("username").asString()).isEqualTo(a.username());

        // 未登录也应 200（接口公开），且不报错
        mockMvc.perform(get("/api/users/" + a.id() + "/following"))
                .andExpect(status().isOk());
    }

    // ── C. 评论互动（完整链路） ──────────────────────

    /**
     * 发一条帖子评论 → 详情能读到（含内容 / post_id / 作者）；同一用户重复点赞是 toggle（第二次取消）。
     * <p>防的坑：评论写入后 count 与列表不一致；点赞不是 toggle 而是累加（刷赞）。
     */
    @Test
    void 帖子评论详情可读到且同一用户重复点赞为取消() throws Exception {
        Actor actor = registerActor("cm");
        long postId = createPost(actor, "评论链路测试帖", "正文");
        long replyId = createPostReply(postId, actor, "第一条评论");

        JsonNode reply = findById(postDetail(postId, actor).path("replies"), replyId);
        assertThat(reply).as("详情接口必须能读到刚发的评论").isNotNull();
        assertThat(reply.path("content").asString()).isEqualTo("第一条评论");
        assertThat(reply.path("post_id").asLong()).isEqualTo(postId);
        assertThat(reply.path("author").asString())
                .as("作者名快照 = 创建时的昵称（新用户昵称初始化为账号名）")
                .isEqualTo(actor.username());
        assertThat(reply.path("like_count").asInt()).isZero();

        // 第一次点赞：liked=true，计数 1
        MvcResult like = mockMvc.perform(post("/api/replies/" + replyId + "/like")
                        .header("Authorization", actor.bearer()))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(body(like).path("liked").asBoolean()).isTrue();
        assertThat(body(like).path("like_count").asInt()).isEqualTo(1);

        // 第二次点赞：toggle 取消，liked=false，计数回到 0
        MvcResult unlike = mockMvc.perform(post("/api/replies/" + replyId + "/like")
                        .header("Authorization", actor.bearer()))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(body(unlike).path("liked").asBoolean()).isFalse();
        assertThat(body(unlike).path("like_count").asInt()).isZero();

        // 详情回读同样是取消后的状态（同一用户只留一条点赞记录）
        JsonNode after = findById(postDetail(postId, actor).path("replies"), replyId);
        assertThat(after.path("liked").asBoolean()).isFalse();
        assertThat(after.path("like_count").asInt()).isZero();
    }

    /**
     * 评论点赞数与点踩数独立计数：点踩不影响点赞数（两者不互斥，可同时为真）。
     * <p>防的坑：点踩被实现成「取消点赞」（互斥），或两者共用一个 count 字段互相覆盖。
     * <p>帖子评论与 Galgame 评论两条链路各测一遍（DAO 不同、Controller 不同）。
     */
    @Test
    void 评论点赞与点踩独立计数互不影响() throws Exception {
        Actor actor = registerActor("vd");
        long postId = createPost(actor, "点赞点踩独立测试帖", "正文");
        long replyId = createPostReply(postId, actor, "点赞点踩独立的评论");

        MvcResult like = mockMvc.perform(post("/api/replies/" + replyId + "/like")
                        .header("Authorization", actor.bearer()))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(body(like).path("like_count").asInt()).isEqualTo(1);

        MvcResult dislike = mockMvc.perform(post("/api/replies/" + replyId + "/dislike")
                        .header("Authorization", actor.bearer()))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(body(dislike).path("disliked").asBoolean()).isTrue();
        assertThat(body(dislike).path("dislike_count").asInt()).isEqualTo(1);

        JsonNode reply = findById(postDetail(postId, actor).path("replies"), replyId);
        assertThat(reply.path("like_count").asInt()).as("点踩后点赞数必须还是 1").isEqualTo(1);
        assertThat(reply.path("dislike_count").asInt()).isEqualTo(1);
        assertThat(reply.path("liked").asBoolean()).isTrue();
        assertThat(reply.path("disliked").asBoolean()).isTrue();

        // Galgame 评论链路同样独立
        Actor admin = registerAdmin("ve");
        long galgameId = createApprovedGalgame(admin);
        long galgameReplyId = createGalgameReply(galgameId, actor, "galgame 点赞点踩独立");

        MvcResult gLike = mockMvc.perform(post("/api/galgame-replies/" + galgameReplyId + "/like")
                        .header("Authorization", actor.bearer()))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(body(gLike).path("liked").asBoolean()).isTrue();
        assertThat(body(gLike).path("like_count").asInt()).isEqualTo(1);

        MvcResult gDislike = mockMvc.perform(post("/api/galgame-replies/" + galgameReplyId + "/dislike")
                        .header("Authorization", actor.bearer()))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(body(gDislike).path("disliked").asBoolean()).isTrue();
        assertThat(body(gDislike).path("dislike_count").asInt()).isEqualTo(1);

        JsonNode gReply = findById(galgameDetail(galgameId, actor).path("replies"), galgameReplyId);
        assertThat(gReply.path("like_count").asInt()).as("点踩后点赞数必须还是 1").isEqualTo(1);
        assertThat(gReply.path("dislike_count").asInt()).isEqualTo(1);
    }

    /**
     * 空内容评论必须 400（纯空白 trim 后同样 400），帖子评论与 Galgame 评论一致。
     * <p>防的坑：空评论写进库，详情页出现空气泡；或空白字符串靠 {@code !isBlank()} 之外的方式校验漏掉。
     */
    @Test
    void 空内容评论返回400() throws Exception {
        Actor actor = registerActor("ec");
        long postId = createPost(actor, "空评论测试帖", "正文");

        MvcResult empty = mockMvc.perform(post("/api/posts/" + postId + "/replies")
                        .header("Authorization", actor.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(map("content", ""))))
                .andExpect(status().isBadRequest())
                .andReturn();
        assertThat(body(empty).path("error").asString()).isEqualTo("回复内容不能为空。");

        // 纯空白：Controller/Service trim 后视为空
        MvcResult blank = mockMvc.perform(post("/api/posts/" + postId + "/replies")
                        .header("Authorization", actor.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(map("content", "   "))))
                .andExpect(status().isBadRequest())
                .andReturn();
        assertThat(body(blank).path("error").asString()).isEqualTo("回复内容不能为空。");

        // 字段缺失（content 不传）也走同一条校验
        mockMvc.perform(post("/api/posts/" + postId + "/replies")
                        .header("Authorization", actor.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());

        Actor admin = registerAdmin("ed");
        long galgameId = createApprovedGalgame(admin);
        MvcResult gEmpty = mockMvc.perform(post("/api/galgames/" + galgameId + "/replies")
                        .header("Authorization", actor.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(map("content", "   "))))
                .andExpect(status().isBadRequest())
                .andReturn();
        assertThat(body(gEmpty).path("error").asString()).isEqualTo("回复内容不能为空。");
    }

    /**
     * 超长评论必须 400：上限 2000 字（含），2001 字被拒；边界 2000 字必须能发出去。
     * <p>防的坑：超长内容直接落库（撑爆详情页/DB 列），或上限写成 {@code >= 2000} 把合法边界值误杀。
     * <p>顺带钉住 Galgame 长评（is_long=true）的 300 字下限——它和 2000 上限共用同一段长度校验。
     */
    @Test
    void 超过2000字的评论返回400而边界2000字可发出() throws Exception {
        Actor actor = registerActor("lg");
        long postId = createPost(actor, "超长评论测试帖", "正文");

        MvcResult tooLong = mockMvc.perform(post("/api/posts/" + postId + "/replies")
                        .header("Authorization", actor.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(map("content", "字".repeat(2001)))))
                .andExpect(status().isBadRequest())
                .andReturn();
        assertThat(body(tooLong).path("error").asString()).isEqualTo("字段长度超出限制。");

        // 边界：正好 2000 字是合法的（上限含 2000）
        mockMvc.perform(post("/api/posts/" + postId + "/replies")
                        .header("Authorization", actor.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(map("content", "字".repeat(2000)))))
                .andExpect(status().isCreated());

        Actor admin = registerAdmin("lh");
        long galgameId = createApprovedGalgame(admin);

        MvcResult gTooLong = mockMvc.perform(post("/api/galgames/" + galgameId + "/replies")
                        .header("Authorization", actor.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(map("content", "字".repeat(2001)))))
                .andExpect(status().isBadRequest())
                .andReturn();
        assertThat(body(gTooLong).path("error").asString()).isEqualTo("字段长度超出限制。");

        // 长评下限：is_long=true 但不足 300 字 → 400（短评不受此限，见上一条 2000 字短评）
        MvcResult longTooShort = mockMvc.perform(post("/api/galgames/" + galgameId + "/replies")
                        .header("Authorization", actor.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(map("content", "字".repeat(300), "is_long", true))))
                .andExpect(status().isBadRequest())
                .andReturn();
        assertThat(body(longTooShort).path("error").asString()).isEqualTo("长评至少需要 300 字。");
    }

    // ── 测试辅助 ────────────────────────────────────

    /**
     * 注册一个 id 一定落在 Long 缓存区间（-128~127）之外的测试用户。
     * <p>Long 引用比较类 bug 的触发前提：只有 id 超过缓存范围，{@code Long.valueOf} 才会箱出不同对象。
     * 全新测试库的自增 id 从 1 开始会命中缓存，让这类 bug「假性通过」——
     * 故这里先把 users 的自增起始值抬到 100000（只影响后续插入，不动任何现有行）。
     */
    private Actor registerActorUncached(String prefix) throws Exception {
        jdbcTemplate.execute("ALTER TABLE users AUTO_INCREMENT = 100000");
        Actor actor = registerActor(prefix);
        assertThat(actor.id())
                .as("用户 id 必须大于 127（Long 缓存上限），否则 Long 引用比较的回归点无法被触发")
                .isGreaterThan(127L);
        return actor;
    }

    /** 发帖（JSON 分支，省略 category/sections 走默认），返回帖子 id */
    private long createPost(Actor actor, String title, String content) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/posts")
                        .header("Authorization", actor.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(map("title", title, "content", content))))
                .andExpect(status().isCreated())
                .andReturn();
        return body(result).path("id").asLong();
    }

    /** 管理员创建一条 Galgame（管理员创建即 approved），返回作品 id */
    private long createApprovedGalgame(Actor admin) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/galgames")
                        .header("Authorization", admin.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(map("name", uniqueName("gal")))))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode created = body(result);
        assertThat(created.path("status").asString())
                .as("管理员创建即上架，后续评论链路依赖 approved 状态")
                .isEqualTo("approved");
        return created.path("id").asLong();
    }

    /** 发一条帖子评论（JSON），返回评论 id */
    private long createPostReply(long postId, Actor actor, String content) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/posts/" + postId + "/replies")
                        .header("Authorization", actor.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(map("content", content))))
                .andExpect(status().isCreated())
                .andReturn();
        return body(result).path("id").asLong();
    }

    /** 发一条 Galgame 评论（JSON），返回评论 id */
    private long createGalgameReply(long galgameId, Actor actor, String content) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/galgames/" + galgameId + "/replies")
                        .header("Authorization", actor.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(map("content", content))))
                .andExpect(status().isCreated())
                .andReturn();
        return body(result).path("id").asLong();
    }

    /** 改昵称（部分更新接口），用于验证关注/粉丝列表真的返回 nickname 字段 */
    private void setNickname(Actor actor, String nickname) throws Exception {
        mockMvc.perform(put("/api/auth/profile")
                        .header("Authorization", actor.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(map("nickname", nickname))))
                .andExpect(status().isOk());
    }

    /** 读帖子详情（viewer 为 null 表示匿名），断言 200 */
    private JsonNode postDetail(long postId, Actor viewer) throws Exception {
        var request = get("/api/posts/" + postId);
        if (viewer != null) {
            request = request.header("Authorization", viewer.bearer());
        }
        MvcResult result = mockMvc.perform(request).andExpect(status().isOk()).andReturn();
        return body(result);
    }

    /** 读 Galgame 详情（viewer 为 null 表示匿名），断言 200 */
    private JsonNode galgameDetail(long galgameId, Actor viewer) throws Exception {
        var request = get("/api/galgames/" + galgameId);
        if (viewer != null) {
            request = request.header("Authorization", viewer.bearer());
        }
        MvcResult result = mockMvc.perform(request).andExpect(status().isOk()).andReturn();
        return body(result);
    }

    /**
     * 在 JSON 数组里按 id 找元素，找不到（或不是数组）返回 null。
     * <p>用下标遍历而非 for-each：JsonNode 的迭代器行为在 Jackson 各版本间有差异，下标最稳。
     */
    private JsonNode findById(JsonNode array, long id) {
        if (array == null || !array.isArray()) {
            return null;
        }
        for (int i = 0; i < array.size(); i++) {
            JsonNode node = array.get(i);
            if (node.path("id").asLong() == id) {
                return node;
            }
        }
        return null;
    }
}
