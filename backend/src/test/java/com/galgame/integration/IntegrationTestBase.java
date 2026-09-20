package com.galgame.integration;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 集成测试基类：启动完整 Spring 上下文（真实 Controller → Service → DAO → MySQL），
 * 用 MockMvc 发 HTTP 请求走完整链路。
 *
 * <p><b>测试库隔离</b>：{@code @ActiveProfiles("test")} 使数据源指向独立的 {@code galgame_test} 库
 * （见 src/test/resources/application-test.properties），绝不会碰开发库 {@code galgame} 的真实数据。
 *
 * <p><b>命名唯一</b>：所有测试账号用 {@link #uniqueName(String)} 生成带运行标签的名字，
 * 避免重复运行/并发类之间撞用户名（users.username 有唯一约束）。
 *
 * <p><b>不依赖凭据</b>：需要管理员身份时用 {@link #registerAdmin(String)}——先注册再直接改库
 * 提升 admin_level，不需要管理员密码，测试代码里不出现任何凭据。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public abstract class IntegrationTestBase {

    /** 测试账号统一密码（满足后端「密码至少 6 位」要求） */
    protected static final String PASSWORD = "TestPass123";

    private static final AtomicInteger SEQ = new AtomicInteger();
    /** 本次 JVM 运行的标签，保证跨次运行也不撞名 */
    private static final long RUN_TAG = System.currentTimeMillis() % 100_000_000L;

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected JsonMapper jsonMapper;

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    /** 一个已注册的测试用户身份 */
    protected record Actor(String username, long id, String token) {
        /** 直接可用的 Authorization 请求头值 */
        String bearer() {
            return "Bearer " + token;
        }
    }

    // ── 测试数据构造 ─────────────────────────────

    /**
     * 生成唯一用户名。后端约束为 2~20 位中英文/数字/下划线，故前缀请控制在 6 字符内。
     */
    protected String uniqueName(String prefix) {
        return prefix + "_" + RUN_TAG + "_" + SEQ.incrementAndGet();
    }

    /** 注册一个普通用户并返回其身份 */
    protected Actor registerActor(String prefix) throws Exception {
        String username = uniqueName(prefix);
        JsonNode res = register(username, PASSWORD);
        return new Actor(username, res.path("user").path("id").asLong(), res.path("token").asString());
    }

    /**
     * 注册一个管理员用户并返回其身份：先正常注册，再直接在库里把 admin_level 提到 1。
     * <p>不调用 /auth/admin-verify，避免测试代码里出现管理员密码。
     * <p>拦截器每次请求都重新查库，故提权后无需重新登录即可生效。
     */
    protected Actor registerAdmin(String prefix) throws Exception {
        Actor actor = registerActor(prefix);
        int updated = jdbcTemplate.update("UPDATE users SET admin_level = 1 WHERE id = ?", actor.id());
        if (updated != 1) {
            throw new IllegalStateException("提升管理员失败，userId=" + actor.id());
        }
        return actor;
    }

    /** 注册，断言 201，返回 {token, user} */
    protected JsonNode register(String username, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(map("username", username, "password", password))))
                .andExpect(status().isCreated())
                .andReturn();
        return body(result);
    }

    /** 登录，返回 {token, user}（不断言状态码，交给调用方） */
    protected JsonNode login(String username, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(map("username", username, "password", password))))
                .andReturn();
        return body(result);
    }

    /** 读取当前用户的萌点数（直接查库，用于断言积分发放） */
    protected int moePointsOf(long userId) {
        Integer points = jdbcTemplate.queryForObject(
                "SELECT moe_points FROM users WHERE id = ?", Integer.class, userId);
        return points == null ? 0 : points;
    }

    // ── 响应/请求体工具 ─────────────────────────

    /** 解析响应体为 JSON（显式 UTF-8，避免中文断言乱码） */
    protected JsonNode body(MvcResult result) {
        try {
            return jsonMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("响应体不是合法 JSON：" + e.getMessage(), e);
        }
    }

    /** 对象序列化为 JSON 字符串 */
    protected String json(Object value) {
        return jsonMapper.writeValueAsString(value);
    }

    /** 构造请求体 Map，支持 null 值：map("name", "x", "score", null) */
    protected Map<String, Object> map(Object... keyValues) {
        if (keyValues.length % 2 != 0) {
            throw new IllegalArgumentException("map(...) 需要成对的 key/value");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            result.put((String) keyValues[i], keyValues[i + 1]);
        }
        return result;
    }
}
