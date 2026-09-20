package com.galgame.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import tools.jackson.databind.JsonNode;

/**
 * 萌点（积分）系统集成测试：每日签到、发帖/评论的每日奖励。
 *
 * <p>覆盖 {@code CheckInController}（手动 token 鉴权 + 幂等签到）与
 * {@code PostService} 中「每日首次发帖 +10 / 评论 +5」的奖励链路，
 * 走 Controller → Service → DAO → MySQL 全链路，并用 {@link #moePointsOf(long)} 直接查库交叉验证。
 *
 * <p><b>幂等依据</b>：{@code daily_rewards} 表唯一键 {@code (user_id, action_type, action_date)} +
 * {@code INSERT IGNORE}，故「同一天同一类型只奖一次」由数据库保证，不依赖应用层状态。
 *
 * <p><b>不依赖残留数据</b>：每个用例都用 {@link #uniqueName(String)} 新建独立账号，
 * 断言只针对「本人相对基线的前后差值」或「本人绝对萌点」，不做全库统计。
 */
@DisplayName("萌点系统：每日签到 / 发帖奖励 / 评论奖励")
class MoePointsIntegrationTest extends IntegrationTestBase {

    // ── 签到 ─────────────────────────────

    @Test
    void 未登录签到返回401() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/check-in"))
                .andExpect(status().isUnauthorized())
                .andReturn();
        assertThat(body(result).path("error").asString()).isEqualTo("请先登录。");
    }

    @Test
    void 未登录查询签到状态返回401() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/check-in/status"))
                .andExpect(status().isUnauthorized())
                .andReturn();
        assertThat(body(result).path("error").asString()).isEqualTo("请先登录。");
    }

    @Test
    void 新用户签到首次加10萌点且库里确实加分() throws Exception {
        Actor actor = registerActor("ci");
        assertThat(moePointsOf(actor.id())).as("新注册用户初始萌点应为 0").isZero();

        MvcResult result = mockMvc.perform(post("/api/check-in")
                        .header("Authorization", actor.bearer()))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode res = body(result);
        assertThat(res.path("ok").asBoolean()).isTrue();
        assertThat(res.path("awarded").asBoolean()).as("今日首次签到应发放奖励").isTrue();
        assertThat(res.path("moe_points").asInt()).as("响应应回显加分会的最新萌点").isEqualTo(10);
        assertThat(moePointsOf(actor.id())).as("查库确认萌点已从 0 变为 10").isEqualTo(10);
    }

    @Test
    void 同一天重复签到幂等且不再加分() throws Exception {
        Actor actor = registerActor("ci2");

        JsonNode first = body(mockMvc.perform(post("/api/check-in")
                        .header("Authorization", actor.bearer()))
                .andExpect(status().isOk())
                .andReturn());
        assertThat(first.path("awarded").asBoolean()).isTrue();

        MvcResult second = mockMvc.perform(post("/api/check-in")
                        .header("Authorization", actor.bearer()))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode res = body(second);
        assertThat(res.path("ok").asBoolean()).as("重复签到不是错误，仍返回 ok").isTrue();
        assertThat(res.path("awarded").asBoolean()).as("今日已签过，应返回 awarded=false").isFalse();
        assertThat(res.path("moe_points").asInt()).as("重复签到不应再加分，仍是 10").isEqualTo(10);
        assertThat(moePointsOf(actor.id())).as("查库确认萌点未被重复累加").isEqualTo(10);
    }

    @Test
    void 签到状态接口正确反映今日签到与萌点() throws Exception {
        Actor actor = registerActor("st");

        // 签到前：今天没签过，萌点 0
        JsonNode before = body(mockMvc.perform(get("/api/check-in/status")
                        .header("Authorization", actor.bearer()))
                .andExpect(status().isOk())
                .andReturn());
        assertThat(before.path("today_checked_in").asBoolean())
                .as("新用户当天尚未签到")
                .isFalse();
        assertThat(before.path("moe_points").asInt()).isZero();

        mockMvc.perform(post("/api/check-in").header("Authorization", actor.bearer()))
                .andExpect(status().isOk());

        // 签到后：状态翻转为已签到，萌点 10
        JsonNode after = body(mockMvc.perform(get("/api/check-in/status")
                        .header("Authorization", actor.bearer()))
                .andExpect(status().isOk())
                .andReturn());
        assertThat(after.path("today_checked_in").asBoolean()).as("签到后状态应为已签到").isTrue();
        assertThat(after.path("moe_points").asInt()).isEqualTo(10);
        assertThat(after.path("moe_points").asInt())
                .as("status 的萌点应与库内一致")
                .isEqualTo(moePointsOf(actor.id()));
    }

    // ── 发帖 / 评论奖励 ───────────────────

    @Test
    void 发帖每日首次加10萌点且当天再发帖不再加分() throws Exception {
        Actor actor = registerActor("post");
        assertThat(moePointsOf(actor.id())).isZero();

        JsonNode first = createPost(actor, uniqueName("pt"));
        assertThat(first.path("id").asLong()).as("发帖应返回新帖 id").isPositive();
        assertThat(moePointsOf(actor.id())).as("每日首次发帖应 +10").isEqualTo(10);

        createPost(actor, uniqueName("pt"));
        assertThat(moePointsOf(actor.id()))
                .as("同一天再发一帖不应重复加分，仍是 10")
                .isEqualTo(10);
    }

    @Test
    void 评论每日首次加5萌点且当天再评论不再加分() throws Exception {
        Actor actor = registerActor("rep");
        long postId = createPost(actor, uniqueName("rp")).path("id").asLong();

        int afterPost = moePointsOf(actor.id());
        assertThat(afterPost).as("基线：发帖后（含发帖 +10）的萌点").isEqualTo(10);

        JsonNode first = createReply(actor, postId, "第一条评论");
        assertThat(first.path("id").asLong()).as("评论应返回新评论 id").isPositive();
        assertThat(moePointsOf(actor.id())).as("每日首次评论应 +5").isEqualTo(afterPost + 5);

        createReply(actor, postId, "第二条评论");
        assertThat(moePointsOf(actor.id()))
                .as("同一天再评论不应重复加分，仍是首次评论后的值")
                .isEqualTo(afterPost + 5);
    }

    @Test
    void 未登录发帖和评论都返回401() throws Exception {
        // 未登录发帖：/api/posts 的 POST 已注册到 AuthInterceptor，应在进入 Controller 前被拦下
        MvcResult postResult = mockMvc.perform(post("/api/posts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(map("category", "话题", "title", "匿名帖", "content", "匿名正文"))))
                .andExpect(status().isUnauthorized())
                .andReturn();
        assertThat(body(postResult).path("error").asString()).isEqualTo("请先登录。");

        // 未登录评论：借一个真实存在的帖子 id，确保 401 来自鉴权而非 404
        Actor actor = registerActor("anon");
        long postId = createPost(actor, uniqueName("an")).path("id").asLong();

        MvcResult replyResult = mockMvc.perform(post("/api/posts/" + postId + "/replies")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(map("content", "匿名评论"))))
                .andExpect(status().isUnauthorized())
                .andReturn();
        assertThat(body(replyResult).path("error").asString()).isEqualTo("请先登录。");
    }

    // ── 多用户隔离 ───────────────────────

    @Test
    void 两个用户的每日签到奖励互不影响() throws Exception {
        Actor a = registerActor("pairA");
        Actor b = registerActor("pairB");

        JsonNode resA = body(mockMvc.perform(post("/api/check-in")
                        .header("Authorization", a.bearer()))
                .andExpect(status().isOk())
                .andReturn());
        assertThat(resA.path("awarded").asBoolean()).as("A 首次签到应加分").isTrue();
        assertThat(resA.path("moe_points").asInt()).isEqualTo(10);

        // A 已签过不影响 B：B 同样能拿到 +10
        JsonNode resB = body(mockMvc.perform(post("/api/check-in")
                        .header("Authorization", b.bearer()))
                .andExpect(status().isOk())
                .andReturn());
        assertThat(resB.path("awarded").asBoolean())
                .as("B 是独立用户，签到同样应发放奖励")
                .isTrue();
        assertThat(resB.path("moe_points").asInt()).isEqualTo(10);

        assertThat(moePointsOf(a.id())).as("A 的萌点").isEqualTo(10);
        assertThat(moePointsOf(b.id())).as("B 的萌点").isEqualTo(10);

        // B 的签到状态与 A 互不干扰
        JsonNode statusB = body(mockMvc.perform(get("/api/check-in/status")
                        .header("Authorization", b.bearer()))
                .andExpect(status().isOk())
                .andReturn());
        assertThat(statusB.path("today_checked_in").asBoolean()).isTrue();
        assertThat(statusB.path("moe_points").asInt()).isEqualTo(10);
    }

    // ── 请求构造 ─────────────────────────

    /** 发帖（JSON 分支，只有 title/content 必填，category 缺省为「话题」），断言 201 并返回帖子对象 */
    private JsonNode createPost(Actor actor, String title) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/posts")
                        .header("Authorization", actor.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(map("category", "话题", "title", title, "content", "萌点集成测试正文"))))
                .andExpect(status().isCreated())
                .andReturn();
        return body(result);
    }

    /** 评论（JSON 分支，content 必填），断言 201 并返回评论对象 */
    private JsonNode createReply(Actor actor, long postId, String content) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/posts/" + postId + "/replies")
                        .header("Authorization", actor.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(map("content", content))))
                .andExpect(status().isCreated())
                .andReturn();
        return body(result);
    }
}
