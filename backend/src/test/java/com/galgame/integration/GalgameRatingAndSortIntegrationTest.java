package com.galgame.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import tools.jackson.databind.JsonNode;

/**
 * Galgame 评分与排序集成测试：走完整 HTTP 链路（Controller → Service → DAO → MySQL）。
 *
 * <p>覆盖四块契约：
 * <ol>
 *   <li><b>评分</b>：未登录 401、0~10 分校验、一人一票（重复 409）、「旧均值×人数 + 新分」增量平均算法；</li>
 *   <li><b>详情回显</b>：{@code rated} / {@code my_score}（历史 bug：曾只返回 rated 不返回 my_score，
 *       导致已评分用户重进详情恒显示 0 星——本类专门回归）；</li>
 *   <li><b>SNAKE_CASE 契约</b>：{@code release_date} 能存下并回显，camelCase {@code releaseDate} 被忽略（历史坑）；</li>
 *   <li><b>列表排序</b>：{@code ?sort=rating/rating_asc/release_date_desc/release_date_asc} 的相对顺序与 NULL 排最后。</li>
 * </ol>
 *
 * <p>⚠️ 排序断言一律只比较「本次造的数据之间的相对先后」，绝不断言全局第 N 条——
 * 测试库（galgame_test）里可能有其它残留数据，绝对位置不可靠。
 */
@DisplayName("Galgame 评分与排序：一人一票 / 增量平均 / 详情回显 / sort 排序")
class GalgameRatingAndSortIntegrationTest extends IntegrationTestBase {

    // ── 鉴权 ─────────────────────────────

    @Test
    void 未登录评分返回401() throws Exception {
        Actor admin = registerAdmin("adm");
        long galgameId = createApprovedGalgame(admin, uniqueName("gg"));

        MvcResult result = postRating(galgameId, null, map("score", 8.0));

        assertThat(result.getResponse().getStatus()).as("未登录不得评分").isEqualTo(401);
        assertThat(body(result).path("error").asString()).isEqualTo("请先登录。");
    }

    // ── 首次评分：人数与平均分 ────────────────

    @Test
    void 首次评分写入人数与平均分() throws Exception {
        Actor admin = registerAdmin("adm");
        Actor rater = registerActor("rtr");

        JsonNode created = createGalgame(admin, map("name", uniqueName("gg")));
        assertThat(created.path("status").asString()).as("管理员创建应直接上架").isEqualTo("approved");
        assertThat(created.path("rating_avg").isNull()).as("新作品未评分时 rating_avg 为 null").isTrue();
        assertThat(created.path("rating_count").asLong()).isZero();
        long galgameId = created.path("id").asLong();

        JsonNode rated = rateOk(galgameId, rater, 6.0);

        assertThat(rated.path("rating_count").asLong()).as("首个评分者计入人数").isEqualTo(1);
        assertThat(rated.path("rating_avg").asDouble())
                .as("首次评分平均分直接等于该分")
                .isCloseTo(6.0, within(0.011));
    }

    // ── 一人一票 ───────────────────────────

    @Test
    void 同一用户重复评分返回409且不重复计数() throws Exception {
        Actor admin = registerAdmin("adm");
        Actor rater = registerActor("rtr");
        long galgameId = createApprovedGalgame(admin, uniqueName("gg"));
        rateOk(galgameId, rater, 6.0);

        MvcResult conflict = postRating(galgameId, rater, map("score", 7.0));

        assertThat(conflict.getResponse().getStatus()).as("一人一票，重复评分应冲突").isEqualTo(409);
        assertThat(body(conflict).path("error").asString()).isEqualTo("你已经评过分了。");

        JsonNode detail = detail(galgameId, rater);
        assertThat(detail.path("rating_count").asLong()).as("被拒的重复评分不得增加人数").isEqualTo(1);
        assertThat(detail.path("rating_avg").asDouble())
                .as("被拒的重复评分不得污染平均分")
                .isCloseTo(6.0, within(0.011));
        assertThat(detail.path("my_score").asDouble())
                .as("被拒的重复评分不得覆盖已投的分数")
                .isCloseTo(6.0, within(0.011));
    }

    // ── 增量平均算法 ────────────────────────

    @Test
    void 第二人评分后按增量公式更新平均分() throws Exception {
        Actor admin = registerAdmin("adm");
        Actor first = registerActor("rtr");
        Actor second = registerActor("rtr");
        long galgameId = createApprovedGalgame(admin, uniqueName("gg"));

        rateOk(galgameId, first, 6.0);
        JsonNode after = rateOk(galgameId, second, 10.0);

        assertThat(after.path("rating_count").asLong()).as("两人评分人数为 2").isEqualTo(2);
        assertThat(after.path("rating_avg").asDouble())
                .as("(旧均值 6.0 × 1 人 + 新分 10.0) / 2 人 = 8.0，验证增量平均而非简单覆盖")
                .isCloseTo(8.0, within(0.011));
    }

    // ── 详情回显自己的分（历史 bug 回归）────────

    @Test
    void 已评分用户查看详情回显自己的分数() throws Exception {
        Actor admin = registerAdmin("adm");
        Actor rater = registerActor("rtr");
        long galgameId = createApprovedGalgame(admin, uniqueName("gg"));
        rateOk(galgameId, rater, 6.0);

        JsonNode detail = detail(galgameId, rater);

        assertThat(detail.path("rated").asBoolean()).as("已评分则 rated 为 true").isTrue();
        assertThat(detail.path("my_score").isNumber())
                .as("详情必须回显当前用户自己的评分（历史 bug：只返回 rated 不返回 my_score → 重进详情恒 0 星）")
                .isTrue();
        assertThat(detail.path("my_score").asDouble()).isCloseTo(6.0, within(0.011));
    }

    @Test
    void 未评分用户查看详情rated为false且无自己的分() throws Exception {
        Actor admin = registerAdmin("adm");
        Actor rater = registerActor("rtr");
        Actor bystander = registerActor("rtr");
        long galgameId = createApprovedGalgame(admin, uniqueName("gg"));
        rateOk(galgameId, rater, 6.0);

        // 作品已有人评分（全局平均分非空），但旁观者自己没评过
        JsonNode fromBystander = detail(galgameId, bystander);
        assertThat(fromBystander.path("rating_count").asLong()).as("作品本身已有 1 人评分").isEqualTo(1);
        assertThat(fromBystander.path("rated").asBoolean()).as("旁观者未评分 → rated=false").isFalse();
        assertNullOrAbsent(fromBystander.path("my_score"), "未评分用户不应拿到 my_score");

        // 未登录访客同理：rated=false 且无 my_score
        JsonNode anonymous = detail(galgameId, null);
        assertThat(anonymous.path("rated").asBoolean()).as("未登录访客 rated=false").isFalse();
        assertNullOrAbsent(anonymous.path("my_score"), "未登录访客不应拿到 my_score");
    }

    // ── 取值范围校验 ────────────────────────

    @Test
    void 评分越界与缺分返回400且不产生评分记录() throws Exception {
        Actor admin = registerAdmin("adm");
        Actor rater = registerActor("rtr");
        long galgameId = createApprovedGalgame(admin, uniqueName("gg"));

        String message = "评分需在 0~10 分之间。";
        assertThat(rateRejected(galgameId, rater, map("score", 11))).as("11 分越界").isEqualTo(message);
        assertThat(rateRejected(galgameId, rater, map("score", -1))).as("-1 分越界").isEqualTo(message);
        assertThat(rateRejected(galgameId, rater, map("score", 10.5))).as("10.5 分越界").isEqualTo(message);
        assertThat(rateRejected(galgameId, rater, map())).as("缺 score 字段").isEqualTo("请输入评分。");

        // 全部被拒 → 不应留下任何评分痕迹（校验必须先于写库）
        JsonNode after = detail(galgameId, rater);
        assertThat(after.path("rating_count").asLong()).as("被拒的评分不得计数").isZero();
        assertThat(after.path("rating_avg").isNull()).as("被拒的评分不得写入平均分").isTrue();
        assertThat(after.path("rated").asBoolean()).as("被拒的评分不得算作已评").isFalse();

        // 越界被拒后，合法分数仍可正常评（说明上面几次请求没有消耗掉「一人一票」的名额）
        assertThat(rateOk(galgameId, rater, 7.0).path("rating_count").asLong())
                .as("越界请求不应消耗该用户唯一的一票")
                .isEqualTo(1);
    }

    @Test
    void 评分0分与10分为合法边界() throws Exception {
        Actor admin = registerAdmin("adm");
        Actor rater = registerActor("rtr");

        long max = createApprovedGalgame(admin, uniqueName("gg"));
        JsonNode ten = rateOk(max, rater, 10.0);
        assertThat(ten.path("rating_avg").isNull()).as("边界值 10 必须真的写入平均分").isFalse();
        assertThat(ten.path("rating_avg").asDouble()).isCloseTo(10.0, within(0.011));

        long min = createApprovedGalgame(admin, uniqueName("gg"));
        JsonNode zero = rateOk(min, rater, 0.0);
        assertThat(zero.path("rating_avg").isNull())
                .as("边界值 0 必须真的写入（是 0.00，不是 NULL/未评分）")
                .isFalse();
        assertThat(zero.path("rating_avg").asDouble()).isCloseTo(0.0, within(0.011));
    }

    // ── SNAKE_CASE 契约回归 ──────────────────

    @Test
    void 创建时传snake_case的release_date能存下并在详情回显() throws Exception {
        Actor admin = registerAdmin("adm");
        String name = uniqueName("gg");

        JsonNode created = createGalgame(admin, map("name", name, "release_date", "2015-04-24"));

        assertThat(created.path("release_date").asString())
                .as("创建响应应以 snake_case 回显发售日期")
                .isEqualTo("2015-04-24");
        long galgameId = created.path("id").asLong();
        assertThat(releaseDateInDb(galgameId))
                .as("release_date 必须真正落库（不能只是响应里好看）")
                .isEqualTo("2015-04-24");

        assertThat(detail(galgameId, admin).path("release_date").asString())
                .as("详情回显发售日期")
                .isEqualTo("2015-04-24");
    }

    @Test
    void 创建时传camelCase的releaseDate被忽略存为null() throws Exception {
        Actor admin = registerAdmin("adm");
        String name = uniqueName("gg");

        // 历史坑：SNAKE_CASE 命名策略下 releaseDate 不匹配 record 组件 releaseDate 的对外名（release_date），
        // Jackson 3 默认不因未知属性报错 → 静默丢弃 → DB 写 NULL。此前端契约必须靠测试锁死。
        JsonNode created = createGalgame(admin, map("name", name, "releaseDate", "2015-04-24"));
        long galgameId = created.path("id").asLong();

        assertNullOrAbsent(created.path("release_date"), "camelCase 键被忽略，发售日期应为空");
        assertThat(releaseDateInDb(galgameId))
                .as("camelCase releaseDate 不得落库（这正是历史上发售日期变 NULL 的原因）")
                .isNull();
    }

    // ── 列表排序 ───────────────────────────

    @Test
    void 按评分排序时高分在前未评分排最后() throws Exception {
        Actor admin = registerAdmin("adm");
        Actor rater = registerActor("rtr");
        String prefix = uniqueName("srt");

        long high = createApprovedGalgame(admin, prefix + "_high");
        long mid = createApprovedGalgame(admin, prefix + "_mid");
        long unrated = createApprovedGalgame(admin, prefix + "_none");
        rateOk(high, rater, 9.0);
        rateOk(mid, rater, 5.0);

        JsonNode desc = listBySort("rating");
        int iHigh = indexOfId(desc, high);
        int iMid = indexOfId(desc, mid);
        int iUnrated = indexOfId(desc, unrated);
        assertThat(iHigh).as("本次创建的 3 部作品都应出现在 sort=rating 的结果里").isGreaterThanOrEqualTo(0);
        assertThat(iMid).isGreaterThanOrEqualTo(0);
        assertThat(iUnrated).isGreaterThanOrEqualTo(0);
        assertThat(iHigh).as("9.0 分应排在 5.0 分之前").isLessThan(iMid);
        assertThat(iMid).as("未评分（rating_avg IS NULL）应排在所有已评分作品之后").isLessThan(iUnrated);

        JsonNode asc = listBySort("rating_asc");
        assertThat(indexOfId(asc, mid)).as("升序时 5.0 分应排在 9.0 分之前").isLessThan(indexOfId(asc, high));
        assertThat(indexOfId(asc, high)).as("升序时未评分仍排最后").isLessThan(indexOfId(asc, unrated));
    }

    @Test
    void 按发售日期排序时方向正确且无日期排最后() throws Exception {
        Actor admin = registerAdmin("adm");
        String prefix = uniqueName("sdt");

        long newer = createApprovedGalgameWithReleaseDate(admin, prefix + "_new", "2018-01-01");
        long older = createApprovedGalgameWithReleaseDate(admin, prefix + "_old", "2005-06-30");
        long undated = createApprovedGalgame(admin, prefix + "_none");

        JsonNode desc = listBySort("release_date_desc");
        int iNewer = indexOfId(desc, newer);
        int iOlder = indexOfId(desc, older);
        int iUndated = indexOfId(desc, undated);
        assertThat(iNewer).as("本次创建的 3 部作品都应出现在结果里").isGreaterThanOrEqualTo(0);
        assertThat(iOlder).isGreaterThanOrEqualTo(0);
        assertThat(iUndated).isGreaterThanOrEqualTo(0);
        assertThat(iNewer).as("从新到旧：2018 应排在 2005 之前").isLessThan(iOlder);
        assertThat(iOlder).as("无发售日期（release_date IS NULL）应排在最后").isLessThan(iUndated);

        JsonNode asc = listBySort("release_date_asc");
        assertThat(indexOfId(asc, older)).as("从旧到新：2005 应排在 2018 之前").isLessThan(indexOfId(asc, newer));
        assertThat(indexOfId(asc, newer)).as("升序时无发售日期仍排最后").isLessThan(indexOfId(asc, undated));
    }

    // ── 私有辅助 ─────────────────────────────

    /**
     * 创建作品（JSON body 原样透传，便于验证 snake_case / camelCase 键名契约），断言 201 并返回响应体。
     */
    private JsonNode createGalgame(Actor creator, Map<String, Object> requestBody) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/galgames")
                        .header("Authorization", creator.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(requestBody)))
                .andExpect(status().isCreated())
                .andReturn();
        return body(result);
    }

    /** 管理员创建作品（创建即上架 approved），返回新作品 id */
    private long createApprovedGalgame(Actor admin, String name) throws Exception {
        JsonNode created = createGalgame(admin, map("name", name));
        assertThat(created.path("status").asString()).as("管理员创建应直接上架").isEqualTo("approved");
        return created.path("id").asLong();
    }

    /** 管理员创建带发售日期的作品（键名固定 snake_case release_date） */
    private long createApprovedGalgameWithReleaseDate(Actor admin, String name, String releaseDate) throws Exception {
        JsonNode created = createGalgame(admin, map("name", name, "release_date", releaseDate));
        assertThat(created.path("status").asString()).as("管理员创建应直接上架").isEqualTo("approved");
        assertThat(created.path("release_date").asString()).isEqualTo(releaseDate);
        return created.path("id").asLong();
    }

    /** 发评分请求（不校验状态码）；actor 为 null 表示不带登录态 */
    private MvcResult postRating(long galgameId, Actor actor, Map<String, Object> requestBody) throws Exception {
        MockHttpServletRequestBuilder builder = post("/api/galgames/" + galgameId + "/rating")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(requestBody));
        if (actor != null) {
            builder = builder.header("Authorization", actor.bearer());
        }
        return mockMvc.perform(builder).andReturn();
    }

    /** 评分并断言 200，返回响应体（含最新 rating_avg / rating_count） */
    private JsonNode rateOk(long galgameId, Actor actor, double score) throws Exception {
        MvcResult result = postRating(galgameId, actor, map("score", score));
        assertThat(result.getResponse().getStatus())
                .as("评分 %.1f 应成功", score)
                .isEqualTo(200);
        return body(result);
    }

    /** 评分并断言 400，返回错误消息文本 */
    private String rateRejected(long galgameId, Actor actor, Map<String, Object> requestBody) throws Exception {
        MvcResult result = postRating(galgameId, actor, requestBody);
        assertThat(result.getResponse().getStatus())
                .as("非法评分应返回 400，实际 body=%s", body(result))
                .isEqualTo(400);
        return body(result).path("error").asString();
    }

    /** 作品详情（viewer 为 null 表示匿名访问），断言 200 并返回详情 JSON */
    private JsonNode detail(long galgameId, Actor viewer) throws Exception {
        MockHttpServletRequestBuilder builder = get("/api/galgames/" + galgameId);
        if (viewer != null) {
            builder = builder.header("Authorization", viewer.bearer());
        }
        MvcResult result = mockMvc.perform(builder).andExpect(status().isOk()).andReturn();
        return body(result);
    }

    /** 列表接口（公开，仅 approved）按 sort 取值取一页，断言 200 且响应是 JSON 数组 */
    private JsonNode listBySort(String sort) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/galgames").param("sort", sort))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode list = body(result);
        assertThat(list.isArray()).as("列表接口应返回 JSON 数组（sort=%s）", sort).isTrue();
        return list;
    }

    /** 在列表中定位某 id 的下标；不存在返回 -1（排序断言只看相对顺序，不看绝对位置） */
    private static int indexOfId(JsonNode list, long galgameId) {
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).path("id").asLong() == galgameId) {
                return i;
            }
        }
        return -1;
    }

    /** 直接查库读取发售日期（YYYY-MM-DD 文本，未存下为 null），验证落库而不只是响应回显 */
    private String releaseDateInDb(long galgameId) {
        return jdbcTemplate.queryForObject(
                "SELECT DATE_FORMAT(release_date, '%Y-%m-%d') FROM galgames WHERE id = ?",
                String.class, galgameId);
    }

    /** 断言字段为空：容忍「键存在值为 null」与「键被 NON_NULL 省略」两种实现，避免测试锁死序列化配置 */
    private static void assertNullOrAbsent(JsonNode node, String message) {
        assertThat(node.isNull() || node.isMissingNode()).as(message).isTrue();
    }
}
