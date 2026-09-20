package com.galgame.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.galgame.auth.TokenService;

/**
 * {@link TokenService#sha256Hex(String)} 单元测试：token 哈希的确定性与编码正确性。
 * <p>纯单元测试——只调静态方法，不启动 Spring、不连数据库、无 mock。
 * <p>未覆盖：实例方法 {@code createToken / deleteToken / resolveUserId} 依赖
 * {@code AuthTokenDao}（写库 / 查库），属集成测试范围。
 */
@DisplayName("TokenService.sha256Hex：静态哈希")
class TokenServiceTest {

    /** SHA-256 十六进制摘要的形状：64 位小写 hex */
    private static final String HEX64 = "[0-9a-f]{64}";

    @Test
    void 同一输入两次调用结果相同() {
        assertThat(TokenService.sha256Hex("hello")).isEqualTo(TokenService.sha256Hex("hello"));
        assertThat(TokenService.sha256Hex("")).isEqualTo(TokenService.sha256Hex(""));
        assertThat(TokenService.sha256Hex("中文用户名")).isEqualTo(TokenService.sha256Hex("中文用户名"));
    }

    @Test
    void 输出是64位小写hex字符串() {
        assertThat(TokenService.sha256Hex("hello")).hasSize(64).matches(HEX64);
        assertThat(TokenService.sha256Hex("")).hasSize(64).matches(HEX64);
        assertThat(TokenService.sha256Hex("a".repeat(2000))).hasSize(64).matches(HEX64);
    }

    @Test
    void 已知向量_空串与ascii匹配标准SHA256() {
        assertThat(TokenService.sha256Hex(""))
                .isEqualTo("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
        assertThat(TokenService.sha256Hex("abc"))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
        assertThat(TokenService.sha256Hex("hello"))
                .isEqualTo("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824");
    }

    @Test
    void 不同输入产出不同哈希() {
        assertThat(TokenService.sha256Hex("abc")).isNotEqualTo(TokenService.sha256Hex("abd"));
        assertThat(TokenService.sha256Hex("abc")).isNotEqualTo(TokenService.sha256Hex("ABC"));
        assertThat(TokenService.sha256Hex("abc")).isNotEqualTo(TokenService.sha256Hex("abc "));
        assertThat(TokenService.sha256Hex("")).isNotEqualTo(TokenService.sha256Hex(" "));
        assertThat(TokenService.sha256Hex("abc")).isNotEqualTo(TokenService.sha256Hex("abcd"));
    }

    @Test
    void 空串输入不抛异常且结果合法() {
        assertThatCode(() -> TokenService.sha256Hex("")).doesNotThrowAnyException();
        assertThat(TokenService.sha256Hex("")).hasSize(64).matches(HEX64);
    }

    @Test
    void 中文输入按UTF8编码() {
        assertThatCode(() -> TokenService.sha256Hex("中文")).doesNotThrowAnyException();
        assertThat(TokenService.sha256Hex("中文"))
                .as("若误用平台默认字符集（本机 Windows 为 GBK），此向量会不一致")
                .isEqualTo("72726d8818f693066ceb69afa364218b692e62ea92b385782363780f47529c21");
        assertThat(TokenService.sha256Hex("中文用户名"))
                .isEqualTo("ad612c2f879f20a9f93ce77674cb993c7cebb43d121160d04ccbe58adc7f080d");
    }

    @Test
    void emoji输入不抛异常且结果合法() {
        // U+1F3AE（视频游戏手柄）：UTF-8 占 4 字节，Java 里是代理对
        assertThatCode(() -> TokenService.sha256Hex("🎮")).doesNotThrowAnyException();
        assertThat(TokenService.sha256Hex("🎮"))
                .hasSize(64)
                .matches(HEX64)
                .isEqualTo("5928d14b4d45263d4964dfd301c84ed2674ca8b4b698c5efeb88fb86076d2bf9");
    }

    @Test
    void 中文与emoji混合输入结果合法() {
        String mixed = "用户🎮abc中文";
        assertThat(TokenService.sha256Hex(mixed)).hasSize(64).matches(HEX64);
        assertThat(TokenService.sha256Hex(mixed)).isEqualTo(TokenService.sha256Hex(mixed));
        assertThat(TokenService.sha256Hex(mixed)).isNotEqualTo(TokenService.sha256Hex("用户abc中文"));
    }

    @Test
    void 任意长度的输入输出长度恒为64() {
        for (int len : new int[] {0, 1, 55, 56, 63, 64, 65, 1000}) {
            assertThat(TokenService.sha256Hex("x".repeat(len)))
                    .as("输入长度 %d", len)
                    .hasSize(64)
                    .matches(HEX64);
        }
    }

    @Test
    void null输入抛NPE_调用方不得传null() {
        // 实测行为：sha256Hex 未做空值防御，null 会在 input.getBytes(...) 处抛 NPE。
        // 生产代码的 deleteToken / resolveUserId 都先做了 null / isBlank 判断再调用。
        assertThatThrownBy(() -> TokenService.sha256Hex(null))
                .isInstanceOf(NullPointerException.class);
    }
}
