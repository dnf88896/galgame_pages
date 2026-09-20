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
 * Galgame 提交审核链路集成测试（本项目最核心的业务链路）：
 * 提交 → 待审隔离 → 管理员审核（通过 / 拒绝）→ 上架 / 驳回可见 → 萌点发放与防重 → 评分限制。
 *
 * <p>覆盖 Controller → GalgameService → GalgameDao → MySQL 全链路。
 * <p>所有断言只针对本次测试新建的数据（名称由 {@link #uniqueName(String)} 生成，跨次运行不撞名），
 * 绝不断言测试库既有数据的绝对状态——测试库可能有历史残留条目。
 */
@DisplayName("Galgame 提交审核链路：提交 / 待审隔离 / 审核 / 萌点防重 / 评分限制")
class GalgameReviewIntegrationTest extends IntegrationTestBase {

    // ── 创建与待审隔离 ─────────────────────────

    @Test
    void 未登录创建galgame返回401() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/galgames")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(map("name", uniqueName("anon")))))
                .andExpect(status().isUnauthorized())
                .andReturn();
        assertThat(body(result).path("error").asString()).isEqualTo("请先登录。");
    }

    @Test
    void 普通用户创建后状态为pending且公开列表搜不到() throws Exception {
        Actor user = registerActor("ggcr");
        String name = uniqueName("gg");
        JsonNode created = createGalgame(user.bearer(), name);

        assertThat(created.path("id").asLong()).as("创建应返回自增 id").isPositive();
        assertThat(created.path("name").asString()).isEqualTo(name);
        assertThat(created.path("status").asString())
                .as("普通用户提交进入待审核")
                .isEqualTo("pending");
        assertThat(created.path("created_by").asLong()).isEqualTo(user.id());
        assertThat(publicListHas(name))
                .as("pending 条目不得出现在公开列表（只返回 approved）")
                .isFalse();
    }

    // ── 详情可见性 ─────────────────────────────

    @Test
    void pending详情仅创建者可见且他人访问返回404不增浏览数() throws Exception {
        Actor creator = registerActor("ggow");
        Actor other = registerActor("ggot");
        String name = uniqueName("gg");
        long id = createGalgame(creator.bearer(), name).path("id").asLong();
        long viewsBefore = viewCountOf(id);

        // 创建者能看自己的 pending 提交
        MvcResult own = mockMvc.perform(get("/api/galgames/{id}", id)
                        .header("Authorization", creator.bearer()))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(body(own).path("name").asString()).isEqualTo(name);
        assertThat(body(own).path("status").asString()).isEqualTo("pending");

        // 另一个普通用户看同一条 pending：404，且提示与「不存在」一致（不泄露条目存在）
        MvcResult other404 = mockMvc.perform(get("/api/galgames/{id}", id)
                        .header("Authorization", other.bearer()))
                .andExpect(status().isNotFound())
                .andReturn();
        assertThat(body(other404).path("error").asString()).isEqualTo("Galgame 不存在。");

        // 未登录访问同样 404
        mockMvc.perform(get("/api/galgames/{id}", id))
                .andExpect(status().isNotFound());

        assertThat(viewCountOf(id))
                .as("非 approved 条目的详情访问（含创建者本人）一律不增加浏览数")
                .isEqualTo(viewsBefore);
    }

    // ── 待审列表 ───────────────────────────────

    @Test
    void 管理员待审列表可见该条并带提交人而普通用户访问403() throws Exception {
        Actor user = registerActor("ggpd");
        Actor admin = registerAdmin("ggad");
        String name = uniqueName("gg");
        long id = createGalgame(user.bearer(), name).path("id").asLong();

        MvcResult result = mockMvc.perform(get("/api/galgames/pending")
                        .header("Authorization", admin.bearer()))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode found = findByName(body(result), name);
        assertThat(found).as("管理员待审列表应包含刚提交的条目").isNotNull();
        assertThat(found.path("id").asLong()).isEqualTo(id);
        assertThat(found.path("status").asString()).isEqualTo("pending");
        assertThat(found.path("creator").asString())
                .as("待审列表须带提交人（昵称优先，新用户昵称=账号名）")
                .isEqualTo(user.username());

        MvcResult forbidden = mockMvc.perform(get("/api/galgames/pending")
                        .header("Authorization", user.bearer()))
                .andExpect(status().isForbidden())
                .andReturn();
        assertThat(body(forbidden).path("error").asString()).isEqualTo("需要管理员权限。");

        mockMvc.perform(get("/api/galgames/pending"))
                .andExpect(status().isUnauthorized());
    }

    // ── 审核通过 ───────────────────────────────

    @Test
    void 审核通过后公开列表可见且提交者加10萌点() throws Exception {
        Actor user = registerActor("ggap");
        Actor admin = registerAdmin("ggaa");
        String name = uniqueName("gg");
        long id = createGalgame(user.bearer(), name).path("id").asLong();
        assertThat(moePointsOf(user.id())).as("新用户初始萌点为 0").isZero();

        MvcResult result = mockMvc.perform(post("/api/galgames/{id}/review", id)
                        .header("Authorization", admin.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(map("status", "approved"))))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode reviewed = body(result);
        assertThat(reviewed.path("status").asString()).isEqualTo("approved");
        assertThat(reviewed.path("moe_granted").asInt())
                .as("审核人不是提交者，响应不回显 +10（萌点只弹给提交者）")
                .isZero();

        assertThat(publicListHas(name)).as("通过审核后应能在公开列表搜到").isTrue();
        assertThat(moePointsOf(user.id())).as("审核通过给提交者 +10 萌点").isEqualTo(10);
        assertThat(moePointsOf(admin.id())).as("萌点加给提交者而非审核人").isZero();
    }

    @Test
    void 重复审核通过不会重复发放萌点() throws Exception {
        Actor user = registerActor("ggdp");
        Actor admin = registerAdmin("ggda");
        String name = uniqueName("gg");
        long id = createGalgame(user.bearer(), name).path("id").asLong();

        approve(id, admin, "approved");
        assertThat(moePointsOf(user.id())).as("首次通过 +10").isEqualTo(10);

        // 再次审核通过：moe_awarded 已置位，claimMoeAward 失败 → 不再加分（历史上的真实坑）
        MvcResult again = approve(id, admin, "approved");
        assertThat(body(again).path("status").asString()).isEqualTo("approved");
        assertThat(body(again).path("moe_granted").asInt()).isZero();
        assertThat(moePointsOf(user.id()))
                .as("同一条目重复通过审核不得重复发放萌点")
                .isEqualTo(10);
    }

    @Test
    void 拒绝后再通过也不重复发放萌点() throws Exception {
        Actor user = registerActor("ggrp");
        Actor admin = registerAdmin("ggra");
        String name = uniqueName("gg");
        long id = createGalgame(user.bearer(), name).path("id").asLong();

        approve(id, admin, "approved");
        assertThat(moePointsOf(user.id())).isEqualTo(10);

        // 拒绝（不发萌点）→ 再次通过（moe_awarded 已置 1，仍不重复加）
        reject(id, admin, "理由：内容需要补充。");
        assertThat(moePointsOf(user.id())).as("拒绝不发放萌点").isEqualTo(10);

        approve(id, admin, "approved");
        assertThat(moePointsOf(user.id()))
                .as("拒绝后重新通过也不得重复发放萌点")
                .isEqualTo(10);
    }

    // ── 审核拒绝 ───────────────────────────────

    @Test
    void 拒绝必填理由且创建者详情可见拒绝理由() throws Exception {
        Actor user = registerActor("ggrj");
        Actor admin = registerAdmin("ggrja");
        String name = uniqueName("gg");
        long id = createGalgame(user.bearer(), name).path("id").asLong();

        // 不带 reason → 400，且状态仍是 pending（校验先于落库）
        MvcResult noReason = mockMvc.perform(post("/api/galgames/{id}/review", id)
                        .header("Authorization", admin.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(map("status", "rejected"))))
                .andExpect(status().isBadRequest())
                .andReturn();
        assertThat(body(noReason).path("error").asString()).isEqualTo("请填写拒绝理由。");

        // 只填空格同样视为没填理由
        MvcResult blankReason = mockMvc.perform(post("/api/galgames/{id}/review", id)
                        .header("Authorization", admin.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(map("status", "rejected", "reason", "   "))))
                .andExpect(status().isBadRequest())
                .andReturn();
        assertThat(body(blankReason).path("error").asString()).isEqualTo("请填写拒绝理由。");
        assertThat(statusOf(id)).as("被拒的审核请求不应改动状态").isEqualTo("pending");

        // 带合法 reason → 拒绝成功
        String reason = "含有违规内容，请修改后重新提交。";
        JsonNode rejected = reject(id, admin, reason);
        assertThat(rejected.path("status").asString()).isEqualTo("rejected");
        assertThat(rejected.path("reject_reason").asString()).isEqualTo(reason);

        // 创建者查看自己 rejected 的详情：能看到 reject_reason
        MvcResult detail = mockMvc.perform(get("/api/galgames/{id}", id)
                        .header("Authorization", user.bearer()))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(body(detail).path("status").asString()).isEqualTo("rejected");
        assertThat(body(detail).path("reject_reason").asString()).isEqualTo(reason);

        assertThat(moePointsOf(user.id())).as("拒绝不发萌点").isZero();
        assertThat(publicListHas(name)).as("被拒条目不进公开列表").isFalse();
    }

    // ── 管理员创建即上架 ───────────────────────

    @Test
    void 管理员创建直接approved并给自己加10萌点() throws Exception {
        Actor admin = registerAdmin("ggca");
        String name = uniqueName("gg");
        assertThat(moePointsOf(admin.id())).as("管理员新账号初始萌点为 0").isZero();

        JsonNode created = createGalgame(admin.bearer(), name);

        assertThat(created.path("status").asString())
                .as("管理员提交无需审核，直接上架")
                .isEqualTo("approved");
        assertThat(created.path("created_by").asLong()).isEqualTo(admin.id());
        assertThat(publicListHas(name)).as("管理员创建即上架，公开列表可见").isTrue();
        assertThat(moePointsOf(admin.id()))
                .as("管理员创建即上架，自己（作为提交者）同样 +10 萌点")
                .isEqualTo(10);
    }

    // ── 评分限制 ───────────────────────────────

    @Test
    void pending条目不能评分且评分不落库() throws Exception {
        Actor user = registerActor("ggrt");
        String name = uniqueName("gg");
        long id = createGalgame(user.bearer(), name).path("id").asLong();

        MvcResult result = mockMvc.perform(post("/api/galgames/{id}/rating", id)
                        .header("Authorization", user.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(map("score", 8))))
                .andExpect(status().isBadRequest())
                .andReturn();
        assertThat(body(result).path("error").asString())
                .isEqualTo("该条目尚未通过审核，不能评分。");
        assertThat(ratingCountInDbOf(id)).as("被拒的评分不得落库").isZero();

        // 上架后同一个人可以正常评分（对照：证明上面的 400 是状态限制而非接口不可用）
        Actor admin = registerAdmin("ggra2");
        approve(id, admin, "approved");
        mockMvc.perform(post("/api/galgames/{id}/rating", id)
                        .header("Authorization", user.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(map("score", 8))))
                .andExpect(status().isOk());
        assertThat(ratingCountInDbOf(id)).isEqualTo(1);
    }

    // ── 测试辅助 ───────────────────────────────

    /**
     * 以指定身份创建 galgame（最小合法 body：只传 name，其余字段均可空），断言 201 并返回响应体。
     * <p>name 必填且 ≤200；description ≤2000、staff ≤200、image ≤500、links 每项 label≤50/url≤500、
     * categories 须为合法 gg-* section_key、tag_ids 须为已上架标签、related_ids 须为已上架作品——
     * 以上均可省略，故最小 body 只有 name。
     */
    private JsonNode createGalgame(String bearer, String name) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/galgames")
                        .header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(map("name", name))))
                .andExpect(status().isCreated())
                .andReturn();
        return body(result);
    }

    /** 管理员审核：status=approved，返回响应体（断言 200） */
    private MvcResult approve(long id, Actor admin, String status) throws Exception {
        return mockMvc.perform(post("/api/galgames/{id}/review", id)
                        .header("Authorization", admin.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(map("status", status))))
                .andExpect(status().isOk())
                .andReturn();
    }

    /** 管理员拒绝并带理由，返回响应体（断言 200） */
    private JsonNode reject(long id, Actor admin, String reason) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/galgames/{id}/review", id)
                        .header("Authorization", admin.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(map("status", "rejected", "reason", reason))))
                .andExpect(status().isOk())
                .andReturn();
        return body(result);
    }

    /** 公开列表按名称搜索（?q=），判断结果中是否存在「名称完全相同」的条目 */
    private boolean publicListHas(String name) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/galgames").param("q", name))
                .andExpect(status().isOk())
                .andReturn();
        return findByName(body(result), name) != null;
    }

    /** 在 JSON 数组里按 name 精确查找元素，找不到返回 null */
    private JsonNode findByName(JsonNode array, String name) {
        for (JsonNode item : array) {
            if (name.equals(item.path("name").asString())) {
                return item;
            }
        }
        return null;
    }

    /** 直接查库读条目当前审核状态（用于断言被拒的审核请求没有副作用） */
    private String statusOf(long id) {
        return jdbcTemplate.queryForObject("SELECT status FROM galgames WHERE id = ?", String.class, id);
    }

    /** 直接查库读浏览数（pending 详情访问不计数，不能靠响应体自证） */
    private long viewCountOf(long id) {
        Long views = jdbcTemplate.queryForObject("SELECT view_count FROM galgames WHERE id = ?", Long.class, id);
        return views == null ? 0L : views;
    }

    /** 直接查库读该条目的评分数（断言被拒的评分没有落库） */
    private int ratingCountInDbOf(long id) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM galgame_ratings WHERE galgame_id = ?", Integer.class, id);
        return count == null ? 0 : count;
    }
}
