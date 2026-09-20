package com.galgame;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * 基础设施验证：Spring 上下文能起来、schema.sql 能在测试库建表。
 * <p>同时这是「测试隔离」的守卫——断言当前连的是 galgame_test 而不是开发库 galgame，
 * 防止将来有人改配置时把集成测试指回开发库、洗掉真实数据。
 */
@SpringBootTest
@ActiveProfiles("test")
class GalgameBackendApplicationTests {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void 上下文加载成功() {
        assertThat(jdbcTemplate).isNotNull();
    }

    @Test
    void 测试连的是隔离的galgame_test库而不是开发库() {
        String currentDb = jdbcTemplate.queryForObject("SELECT DATABASE()", String.class);
        assertThat(currentDb).isEqualTo("galgame_test");
    }

    @Test
    void schema初始化后核心表已建好() {
        Integer tables = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE()",
                Integer.class);
        assertThat(tables).isNotNull();
        // schema.sql 共十几张表（创建时约 18 张），只断言下限，避免新增表时误报
        assertThat(tables).isGreaterThanOrEqualTo(15);
    }
}
