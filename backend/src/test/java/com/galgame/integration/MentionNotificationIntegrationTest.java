package com.galgame.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import tools.jackson.databind.JsonNode;

/**
 * @提及通知集成测试：发帖正文里的 {@code @用户名} 是否真的送达对方。
 *
 * <p><b>为什么必须端到端测</b>：单元测试 {@code MentionServiceTest} 只能钉住正则解析出的**候选串**，
 * 而「解析出名字」≠「该用户存在」≠「通知写进库了」——后两者要查 UserDao 与 NotificationDao。
 * 本类走完整链路：发帖 → {@code MentionService.notifyMention} → 通知入库 → {@code GET /api/notifications}。
 *
 * <p><b>核心回归</b>：2026-09-20 修复了 {@code MENTION_PATTERN} 的边界——前边界原用 {@code \p{L}}
 * （**含汉字**），使中文正文里紧贴的 @（如「谢谢@某某」，中文论坛高频写法）整段漏解析、通知静默丢失。
 * 下面第 1、2 条钉住这次修复：前者是中文紧贴 @ **之前**，后者是 @ **之后**紧贴中文（靠回退试探解决）。
 */
@DisplayName("@提及通知：发帖 @ 是否送达（含中文紧贴边界回归）")
class MentionNotificationIntegrationTest extends IntegrationTestBase {

    /** 发一条帖子，返回 postId */
    private long publishPost(Actor author, String content) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/posts")
                        .header("Authorization", author.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(map("title", uniqueName("mt"), "content", content))))
                .andExpect(status().isCreated())
                .andReturn();
        return body(result).path("id").asLong();
    }

    /** 某人的通知列表里 type=mention 的条目 */
    private List<JsonNode> mentionsOf(Actor who) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/notifications")
                        .header("Authorization", who.bearer()))
                .andExpect(status().isOk())
                .andReturn();
        List<JsonNode> mentions = new ArrayList<>();
        for (JsonNode n : body(result)) {
            if ("mention".equals(n.path("type").asString())) {
                mentions.add(n);
            }
        }
        return mentions;
    }

    // ── 修复回归 ─────────────────────────────

    @Test
    void 中文紧贴at之前也能送达通知() throws Exception {
        Actor actor = registerActor("mna");
        Actor target = registerActor("mnb");

        // 「谢谢」紧贴 @，中间无空格——修复前这里一个通知都不会产生
        long postId = publishPost(actor, "谢谢@" + target.username() + " 的资源分享");

        List<JsonNode> mentions = mentionsOf(target);
        assertThat(mentions).as("中文紧贴 @ 之前必须能送达（2026-09-20 边界修复）").hasSize(1);
        assertThat(mentions.get(0).path("actor").path("id").asLong()).isEqualTo(actor.id());
        assertThat(mentions.get(0).path("post_id").asLong()).isEqualTo(postId);
    }

    @Test
    void at后紧跟中文也能送达通知_靠回退试探() throws Exception {
        Actor actor = registerActor("mnc");
        Actor target = registerActor("mnd");

        // 候选串会被贪婪解析成「账号名+你好」，正则无法区分，靠 resolveUser 从长到短回退找到真人
        publishPost(actor, "@" + target.username() + "你好，来看看");

        assertThat(mentionsOf(target)).as("@ 后紧贴中文时，回退试探应找到真实用户").hasSize(1);
    }

    // ── 基本行为 ─────────────────────────────

    @Test
    void 中文用户名也能被at到() throws Exception {
        Actor actor = registerActor("mne");
        Actor target = registerActor("用户"); // 用户名允许中文（USERNAME_PATTERN 含 一-龥）

        publishPost(actor, "感谢@" + target.username());

        assertThat(mentionsOf(target)).as("中文用户名（本身含汉字）必须能被 @ 到").hasSize(1);
    }

    @Test
    void at自己不产生通知() throws Exception {
        Actor actor = registerActor("mnf");

        publishPost(actor, "@" + actor.username() + " 自问自答");

        assertThat(mentionsOf(actor)).as("actor 自己 @ 自己不应收到通知").isEmpty();
    }

    @Test
    void 重复at同一人只写一条通知() throws Exception {
        Actor actor = registerActor("mng");
        Actor target = registerActor("mnh");

        publishPost(actor, "@" + target.username() + " 和 @" + target.username() + " 还有 @"
                + target.username());

        assertThat(mentionsOf(target)).as("LinkedHashSet 去重后只应有一条").hasSize(1);
    }

    // ── 不该误报的情况 ───────────────────────

    @Test
    void 邮箱里的at不触发通知() throws Exception {
        Actor actor = registerActor("mni");
        Actor target = registerActor("mnj");

        // 邮箱：@ 前是 ASCII 字母，必须仍被挡住（修边界时不能把邮箱保护一起放松掉）
        publishPost(actor, "联系我 " + target.username() + "@example.com");

        assertThat(mentionsOf(target)).as("邮箱里的 @ 不能被当成提及").isEmpty();
    }

    @Test
    void at不存在的用户名不触发任何通知() throws Exception {
        Actor actor = registerActor("mnk");

        long postId = publishPost(actor, "谢谢@" + uniqueName("ghost") + " 你好");

        // 自己不收到通知即证明没命中任何人（正文里只有一个 @）
        assertThat(mentionsOf(actor)).isEmpty();
        assertThat(postId).isPositive();
    }

    @Test
    void 评论里的at同样会通知() throws Exception {
        Actor author = registerActor("mnl");
        Actor commenter = registerActor("mnm");
        Actor target = registerActor("mnn");

        long postId = publishPost(author, "普通帖子正文");

        mockMvc.perform(post("/api/posts/" + postId + "/replies")
                        .header("Authorization", commenter.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(map("content", "谢谢@" + target.username() + " 提醒"))))
                .andExpect(status().isCreated());

        assertThat(mentionsOf(target)).as("评论（而非仅帖子）里的 @ 也要送达").hasSize(1);
    }
}
