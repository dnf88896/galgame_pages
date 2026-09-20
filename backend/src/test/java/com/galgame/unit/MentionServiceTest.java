package com.galgame.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Matcher;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.galgame.service.MentionService;

/**
 * {@link MentionService} 的 @提及**解析规则**单元测试。
 * <p>被测对象是生产代码里公开的纯常量 {@link MentionService#MENTION_PATTERN}——直接跑真实正则，不是 mock。
 * <p><b>未覆盖</b>：唯一的方法 {@code notifyMention(long, Long, Long, String, String)} 依赖
 * {@code UserDao} / {@code PostDao} / {@code NotificationDao} 三个 DAO（查用户、查帖子标题、写通知），
 * 无法在纯单元测试里验证，故**不做 mock 式的「假测试」**，留给集成测试。因此下列行为不在本测试范围内：
 * <ul>
 *   <li>{@code userDao.findByUsername(username)} 命中与否（正则解析出名字 ≠ 该用户存在）</li>
 *   <li>actor 自己 @ 自己不通知（{@code user.id() != actorId} 过滤）</li>
 *   <li>同一人被 @ 多次只写一条通知（{@code LinkedHashSet} 去重）</li>
 *   <li>{@code title == null && postId != null} 时反查帖子标题</li>
 *   <li>正文超 500 字截断、异常静默不抛</li>
 * </ul>
 */
@DisplayName("MentionService：@提及正则解析规则")
class MentionServiceTest {

    /**
     * 提取文本中被识别的全部用户名（保持出现顺序，重复保留）。
     * <p>等价于 {@code notifyMention} 里 {@code while (matcher.find()) matcher.group(1)} 的那一段。
     */
    private static List<String> extract(String content) {
        List<String> names = new ArrayList<>();
        Matcher matcher = MentionService.MENTION_PATTERN.matcher(content);
        while (matcher.find()) {
            names.add(matcher.group(1));
        }
        return names;
    }

    @Test
    void 正常的at用户名被解析出来() {
        assertThat(extract("你好 @alice 在吗")).containsExactly("alice");
        assertThat(extract("@alice")).containsExactly("alice");
        assertThat(extract("a @alice, b @bob!")).containsExactly("alice", "bob");
        assertThat(extract("@alice行尾")).containsExactly("alice行尾"); // 见「后接中文」测试
    }

    @Test
    void 文本中没有at时返回空() {
        assertThat(extract("今天天气不错")).isEmpty();
        assertThat(extract("")).isEmpty();
        assertThat(extract("   ")).isEmpty();
        assertThat(extract("no mention here")).isEmpty();
    }

    @Test
    void at后跟空白或标点时的处理() {
        assertThat(extract("@alice，你好")).as("全角逗号不是用户名字符").containsExactly("alice");
        assertThat(extract("(@alice)")).containsExactly("alice");
        assertThat(extract("@alice。")).containsExactly("alice");
        assertThat(extract("@alice: 你好")).containsExactly("alice");
        assertThat(extract("引用：@alice\n下一行")).containsExactly("alice");
    }

    @Test
    void 中文紧贴at之前也能解析出来() {
        // 2026-09-20 修复：前边界由 (?<![\p{L}\p{N}_]) 改为 (?<![A-Za-z0-9_])。
        // 原先 \p{L} 把汉字也算作「词字符」，中文正文里紧贴的 @ 被整段忽略——
        // 「谢谢@某某」是中文论坛高频写法，@ 通知会静默丢失。
        assertThat(extract("谢谢@alice")).as("「谢」是中文，改动后不再挡住 @").containsExactly("alice");
        assertThat(extract("回复@alice 你好")).containsExactly("alice");
        assertThat(extract("谢谢 @alice")).containsExactly("alice");
        assertThat(extract("谢谢，@alice")).containsExactly("alice");
    }

    @Test
    void at后紧跟空白不构成提及() {
        assertThat(extract("@ alice")).as("@ 后是空格，用户名不能为空").isEmpty();
        assertThat(extract("a@ b")).isEmpty();
        assertThat(extract("@\n alice")).isEmpty();
    }

    @Test
    void 用户名长度边界为2到20位() {
        assertThat(extract("@a")).as("1 位不匹配").isEmpty();
        assertThat(extract("@ab")).containsExactly("ab");
        assertThat(extract("@" + "a".repeat(20))).as("20 位是上限").containsExactly("a".repeat(20));
        assertThat(extract("@" + "a".repeat(21))).as("超过 20 位不匹配").isEmpty();
    }

    @Test
    void 下划线算用户名字符() {
        assertThat(extract("@alice_bob")).containsExactly("alice_bob");
        assertThat(extract("@a_b")).containsExactly("a_b");
    }

    @Test
    void 邮箱里的at不被当作提及() {
        assertThat(extract("foo@bar.com")).isEmpty();
        assertThat(extract("联系我 foo@bar.com")).isEmpty();
        assertThat(extract("a@bcd")).as("@ 前是字母/数字/下划线都不匹配").isEmpty();
        assertThat(extract("foo_bar@bcd")).isEmpty();
    }

    @Test
    void 路径里的at_前导是斜杠时仍会被匹配() {
        // MENTION_PATTERN 的负向后顾只挡「@ 前是字母/数字/下划线」，因此注释里说的「避免匹配路径里的 @」并未完全成立。
        assertThat(extract("/usr/@home")).as("实际行为：@ 前是 / 仍会被解析").containsExactly("home");
        assertThat(extract("C:@users")).containsExactly("users");
    }

    @Test
    void 重复at同一人会被匹配多次_去重由调用方负责() {
        List<String> names = extract("@alice 和 @alice 还有 @bob");

        assertThat(names).as("正则本身不去重").containsExactly("alice", "alice", "bob");
        assertThat(new LinkedHashSet<>(names))
                .as("notifyMention 用 LinkedHashSet 去重后只写一条通知（该逻辑依赖 DAO，未覆盖）")
                .containsExactly("alice", "bob");
    }

    @Test
    void 用户名大小写敏感视为不同的人() {
        assertThat(extract("@alice @Alice @ALICE")).containsExactly("alice", "Alice", "ALICE");
        // Pattern 未启用 CASE_INSENSITIVE：是否命中真实用户由 notifyMention 里的 findByUsername 决定（未覆盖）
    }

    @Test
    void 中文用户名可被解析() {
        assertThat(extract("@小明 你好")).containsExactly("小明");
        assertThat(extract("@小明,@小红")).containsExactly("小明", "小红");
    }

    @Test
    void 中文用户名后紧跟中文会被贪婪并入() {
        // 正则贪婪 + 中文属 \p{L}：「@用户名」后直接接中文、无分隔符时，后续中文会被并入候选串。
        // 正则无法区分「用户名叫小明你好」与「@小明 + 正文你好」，故不再靠正则而是交给
        // MentionService.resolveUser 从长到短回退试探（「小明你好」查不到 → 试「小明你」→「小明」命中）。
        // resolveUser 依赖 UserDao，属集成测试范围：这里只钉住正则给出的候选串。
        assertThat(extract("@小明你好")).containsExactly("小明你好");
        assertThat(extract("@alice中文")).containsExactly("alice中文");
        assertThat(extract("@小明 你好")).as("加空格候选串最干净").containsExactly("小明");
    }

    @Test
    void 连续两个at时仍能从后一个at解析出用户名() {
        assertThat(extract("@@alice")).containsExactly("alice");
    }

    @Test
    void null文本会让matcher抛NPE_故notifyMention先判空() {
        assertThatThrownBy(() -> MentionService.MENTION_PATTERN.matcher(null))
                .as("MENTION_PATTERN.matcher(null) 抛 NPE；notifyMention 开头 `content == null || content.isBlank()` 即为此")
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void 正则本身不去重不校验用户是否存在() {
        // 「解析出名字」与「用户存在」「写通知」是两件事：后者需要 DAO，属集成测试。
        assertThat(extract("@不存在的用户 @ghost_user_404"))
                .as("解析照常输出，是否命中由 findByUsername 决定")
                .containsExactly("不存在的用户", "ghost_user_404");
    }
}
