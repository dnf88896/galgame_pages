package com.galgame.integration;

import static org.assertj.core.api.Assertions.assertThat;
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
 * 账号链路集成测试：注册 / 登录 / 鉴权 / 改资料 / 改密码。
 * <p>覆盖 Controller → Service → DAO → MySQL 全链路，以及 AuthInterceptor 的鉴权行为。
 */
@DisplayName("账号链路：注册 / 登录 / 鉴权 / 资料")
class AuthIntegrationTest extends IntegrationTestBase {

    // ── 注册 ─────────────────────────────

    @Test
    void 注册成功返回token和用户信息且初始萌点为0() throws Exception {
        String username = uniqueName("reg");
        JsonNode res = register(username, PASSWORD);

        assertThat(res.path("token").asString())
                .as("token 应为 64 位随机 hex")
                .hasSize(64)
                .matches("[0-9a-f]{64}");
        assertThat(res.path("user").path("username").asString()).isEqualTo(username);
        assertThat(res.path("user").path("nickname").asString())
                .as("新建用户昵称初始化为账号名")
                .isEqualTo(username);
        assertThat(res.path("user").path("moe_points").asInt()).isZero();
        assertThat(res.path("user").path("admin_level").asInt()).isZero();
        assertThat(res.path("user").has("password_hash"))
                .as("密码哈希绝不能对外序列化")
                .isFalse();
    }

    @Test
    void 用户名过短被拒() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(map("username", "a", "password", PASSWORD))))
                .andExpect(status().isBadRequest())
                .andReturn();
        assertThat(body(result).path("error").asString()).isEqualTo("用户名需为 2~20 位中英文、数字或下划线。");
    }

    @Test
    void 用户名含非法字符被拒() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(map("username", "bad name!", "password", PASSWORD))))
                .andExpect(status().isBadRequest())
                .andReturn();
        assertThat(body(result).path("error").asString()).isEqualTo("用户名需为 2~20 位中英文、数字或下划线。");
    }

    @Test
    void 密码短于6位被拒() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(map("username", uniqueName("pw"), "password", "12345"))))
                .andExpect(status().isBadRequest())
                .andReturn();
        assertThat(body(result).path("error").asString()).isEqualTo("密码至少 6 位。");
    }

    @Test
    void 重复用户名注册返回409() throws Exception {
        Actor actor = registerActor("dup");

        MvcResult result = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(map("username", actor.username(), "password", PASSWORD))))
                .andExpect(status().isConflict())
                .andReturn();
        assertThat(body(result).path("error").asString()).isEqualTo("用户名已被使用。");
    }

    // ── 登录 ─────────────────────────────

    @Test
    void 登录成功返回可用token() throws Exception {
        Actor actor = registerActor("login");
        JsonNode res = login(actor.username(), PASSWORD);

        assertThat(res.path("token").asString()).hasSize(64);
        assertThat(res.path("user").path("id").asLong()).isEqualTo(actor.id());
    }

    @Test
    void 密码错误登录返回401() throws Exception {
        Actor actor = registerActor("wrongpw");
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(map("username", actor.username(), "password", "NotThePassword"))))
                .andExpect(status().isUnauthorized())
                .andReturn();
        assertThat(body(result).path("error").asString()).isEqualTo("用户名或密码错误。");
    }

    @Test
    void 不存在的用户登录返回401且不泄露账号是否存在() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(map("username", uniqueName("ghost"), "password", PASSWORD))))
                .andExpect(status().isUnauthorized())
                .andReturn();
        assertThat(body(result).path("error").asString())
                .as("用户不存在与密码错误应返回同一句提示，避免账号枚举")
                .isEqualTo("用户名或密码错误。");
    }

    // ── 鉴权 ─────────────────────────────

    @Test
    void 未登录访问me返回401() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/auth/me"))
                .andExpect(status().isUnauthorized())
                .andReturn();
        assertThat(body(result).path("error").asString()).isEqualTo("请先登录。");
    }

    @Test
    void 伪造token访问me返回401() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/auth/me")
                        .header("Authorization", "Bearer " + "f".repeat(64)))
                .andExpect(status().isUnauthorized())
                .andReturn();
        assertThat(body(result).path("error").asString()).isEqualTo("请先登录。");
    }

    @Test
    void 带有效token访问me返回当前用户() throws Exception {
        Actor actor = registerActor("me");

        MvcResult result = mockMvc.perform(get("/api/auth/me")
                        .header("Authorization", actor.bearer()))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(body(result).path("id").asLong()).isEqualTo(actor.id());
        assertThat(body(result).path("username").asString()).isEqualTo(actor.username());
    }

    // ── 改资料 ───────────────────────────

    @Test
    void 修改资料只更新出现的字段且缺省字段保持原值() throws Exception {
        Actor actor = registerActor("prof");

        mockMvc.perform(put("/api/auth/profile")
                        .header("Authorization", actor.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(map("bio", "我的签名"))))
                .andExpect(status().isOk());

        // 只传 hide_favorites：bio 不应被清空（部分更新语义）
        MvcResult result = mockMvc.perform(put("/api/auth/profile")
                        .header("Authorization", actor.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(map("hide_favorites", "1"))))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode user = body(result);
        assertThat(user.path("bio").asString()).as("bio 缺省不应被清空").isEqualTo("我的签名");
        assertThat(user.path("hide_favorites").asInt()).isEqualTo(1);
    }

    @Test
    void 昵称超过32个字符被拒() throws Exception {
        Actor actor = registerActor("nick");

        MvcResult result = mockMvc.perform(put("/api/auth/profile")
                        .header("Authorization", actor.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(map("nickname", "称".repeat(33)))))
                .andExpect(status().isBadRequest())
                .andReturn();
        assertThat(body(result).path("error").asString()).isEqualTo("昵称最长 32 个字符。");
    }

    // ── 改密码 ───────────────────────────

    @Test
    void 改密码后旧token立即失效且新密码可登录() throws Exception {
        Actor actor = registerActor("chpw");
        String newPassword = "BrandNewPass456";

        mockMvc.perform(put("/api/auth/password")
                        .header("Authorization", actor.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(map("old_password", PASSWORD, "new_password", newPassword))))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/auth/me").header("Authorization", actor.bearer()))
                .andExpect(status().isUnauthorized());

        assertThat(login(actor.username(), newPassword).path("token").asString())
                .as("新密码应能登录")
                .hasSize(64);
    }

    @Test
    void 改密码时旧密码错误被拒且原密码仍可用() throws Exception {
        Actor actor = registerActor("chpw2");

        MvcResult result = mockMvc.perform(put("/api/auth/password")
                        .header("Authorization", actor.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(map("old_password", "WrongOldPass", "new_password", "AnotherPass789"))))
                .andExpect(status().isBadRequest())
                .andReturn();
        assertThat(body(result).path("error").asString()).isEqualTo("旧密码错误。");

        // 被拒的改密码请求不应使当前 token 失效
        mockMvc.perform(get("/api/auth/me").header("Authorization", actor.bearer()))
                .andExpect(status().isOk());
    }
}
