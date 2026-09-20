package com.galgame.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.galgame.config.AdminLevels;

/**
 * {@link AdminLevels#resolve(String)} 单元测试：管理员密码 → 权限等级映射的**否定侧**。
 * <p>纯单元测试——不启动 Spring、不连数据库、无 mock。
 *
 * <p><b>故意不覆盖的分支</b>：{@code PASSWORD_TO_LEVEL} 中存在「正确密码 → 非 null 等级」的命中分支，
 * 但覆盖它必须在测试源码里写死真实管理员密码明文。本测试**不写任何真实密码**，
 * 因此该分支（以及「密码正确时返回 1/等级随密码变化」）留给需要凭据的环境/人工验证。
 * 真实密码只应存在于生产代码与服务端配置中，不应出现在测试、日志或文档里。
 */
@DisplayName("AdminLevels：管理员密码映射（仅否定侧）")
class AdminLevelsTest {

    @Test
    void resolve_null返回null() {
        assertThat(AdminLevels.resolve(null)).isNull();
    }

    @Test
    void resolve_空串返回null() {
        assertThat(AdminLevels.resolve("")).isNull();
    }

    @Test
    void resolve_空白串返回null() {
        assertThat(AdminLevels.resolve(" ")).isNull();
        assertThat(AdminLevels.resolve("   ")).isNull();
        assertThat(AdminLevels.resolve("\t")).isNull();
        assertThat(AdminLevels.resolve("\n")).isNull();
    }

    @Test
    void resolve_显然错误的密码返回null() {
        assertThat(AdminLevels.resolve("显然错误的密码")).isNull();
    }

    @Test
    void resolve_多种错误输入都返回null() {
        List<String> wrongPasswords = List.of(
                "admin",
                "123456",
                "password",
                "wrong-password",
                "not-the-admin-password",
                "不是管理员密码",
                "x".repeat(500));
        for (String wrong : wrongPasswords) {
            assertThat(AdminLevels.resolve(wrong))
                    .as("错误密码 %s 不应映射到任何等级", wrong)
                    .isNull();
        }
    }

    @Test
    void resolve_不做trim_带空格的输入不匹配() {
        // 实测行为：直接 Map.get，不 trim、不做规范化 —— 前后加空格即视为不同密码。
        assertThat(AdminLevels.resolve(" admin ")).isNull();
        assertThat(AdminLevels.resolve("\tadmin\n")).isNull();
    }

    @Test
    void resolve_大小写敏感() {
        // 实测行为：Map.get 精确匹配，大小写敏感。
        assertThat(AdminLevels.resolve("ADMIN")).isNull();
        assertThat(AdminLevels.resolve("Admin")).isNull();
    }

    @Test
    void resolve_对同一错误输入可重复调用且结果稳定() {
        assertThatCode(() -> {
            assertThat(AdminLevels.resolve("显然错误的密码")).isNull();
            assertThat(AdminLevels.resolve("显然错误的密码")).isNull();
        }).doesNotThrowAnyException();
    }

    @Test
    void 工具类不可实例化() {
        assertThatThrownBy(() -> AdminLevels.class.getDeclaredConstructor().newInstance())
                .as("私有构造器：AdminLevels 是纯静态工具类")
                .isInstanceOf(IllegalAccessException.class);
    }
}
