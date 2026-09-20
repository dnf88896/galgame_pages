package com.galgame.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.galgame.constants.TagConstants;
import com.galgame.constants.TagConstants.Category;
import com.galgame.constants.TagConstants.Section;

/**
 * {@link TagConstants} 单元测试：四大类 / 小分支常量表、存在性判断与 section→category 映射。
 * <p>纯单元测试——常量表在静态块里构建，不启动 Spring、不连数据库、无 mock。
 */
@DisplayName("TagConstants：四大类与小分支常量表")
class TagConstantsTest {

    // ── 辅助 ─────────────────────────────

    /** 展开全部大类下的小分支（顺序：大类书写顺序 → 小分支书写顺序） */
    private static List<Section> allSections() {
        List<Section> all = new ArrayList<>();
        for (Category c : TagConstants.categories()) {
            all.addAll(c.sections());
        }
        return all;
    }

    private static List<Section> sectionsOf(String categoryKey) {
        return TagConstants.categories().stream()
                .filter(c -> c.key().equals(categoryKey))
                .findFirst()
                .orElseThrow(() -> new AssertionError("不存在的大类: " + categoryKey))
                .sections();
    }

    // ── isSectionKey ─────────────────────

    @Test
    void isSectionKey对合法小分支key返回true() {
        assertThat(TagConstants.isSectionKey("gg-type-game")).isTrue();
        assertThat(TagConstants.isSectionKey("g-walkthrough")).isTrue();
        assertThat(TagConstants.isSectionKey("t-ai")).isTrue();
        assertThat(TagConstants.isSectionKey("o-forum")).isTrue();
        assertThat(TagConstants.isSectionKey("gg-work-unclassified")).isTrue();
    }

    @Test
    void isSectionKey对非法key返回false() {
        assertThat(TagConstants.isSectionKey("not-a-key")).isFalse();
        assertThat(TagConstants.isSectionKey("gg-type-adv")).as("kungal 无此标签，常见误用").isFalse();
        assertThat(TagConstants.isSectionKey("galgame")).as("大类 key 不是小分支").isFalse();
        assertThat(TagConstants.isSectionKey("technique")).isFalse();
        assertThat(TagConstants.isSectionKey("galgame-resource")).isFalse();
        assertThat(TagConstants.isSectionKey("G-TYPE-GAME")).as("大小写敏感").isFalse();
        assertThat(TagConstants.isSectionKey("g-walkthroug")).as("拼写接近但不等于").isFalse();
        assertThat(TagConstants.isSectionKey(" g-walkthrough ")).as("不做 trim").isFalse();
    }

    @Test
    void isSectionKey对null和空白返回false() {
        assertThat(TagConstants.isSectionKey(null)).isFalse();
        assertThat(TagConstants.isSectionKey("")).isFalse();
        assertThat(TagConstants.isSectionKey(" ")).isFalse();
        assertThat(TagConstants.isSectionKey("\t\n")).isFalse();
    }

    // ── isCategoryKey ────────────────────

    @Test
    void isCategoryKey对四大类key返回true() {
        assertThat(TagConstants.isCategoryKey("galgame")).isTrue();
        assertThat(TagConstants.isCategoryKey("technique")).isTrue();
        assertThat(TagConstants.isCategoryKey("others")).isTrue();
        assertThat(TagConstants.isCategoryKey("galgame-resource")).isTrue();
    }

    @Test
    void isCategoryKey对小分支key返回false() {
        assertThat(TagConstants.isCategoryKey("gg-type-game")).as("gg-type-game 是 section 不是 category").isFalse();
        assertThat(TagConstants.isCategoryKey("g-walkthrough")).isFalse();
        assertThat(TagConstants.isCategoryKey("gg-lang-zh-cn")).isFalse();
        assertThat(TagConstants.isCategoryKey("o-other")).isFalse();
    }

    @Test
    void isCategoryKey对未知值null空串返回false() {
        assertThat(TagConstants.isCategoryKey(null)).isFalse();
        assertThat(TagConstants.isCategoryKey("")).isFalse();
        assertThat(TagConstants.isCategoryKey(" ")).isFalse();
        assertThat(TagConstants.isCategoryKey("galgame-resources")).as("多一个 s 不是合法大类").isFalse();
        assertThat(TagConstants.isCategoryKey("Galgame")).as("大小写敏感").isFalse();
    }

    // ── categoryKeyOfSection ─────────────

    @Test
    void categoryKeyOfSection正确映射小分支到所属大类() {
        assertThat(TagConstants.categoryKeyOfSection("gg-type-game")).isEqualTo("galgame-resource");
        assertThat(TagConstants.categoryKeyOfSection("g-walkthrough")).isEqualTo("galgame");
        assertThat(TagConstants.categoryKeyOfSection("t-crack")).isEqualTo("technique");
        assertThat(TagConstants.categoryKeyOfSection("o-other")).isEqualTo("others");
    }

    @Test
    void categoryKeyOfSection对未知或null返回null() {
        assertThat(TagConstants.categoryKeyOfSection(null)).isNull();
        assertThat(TagConstants.categoryKeyOfSection("")).isNull();
        assertThat(TagConstants.categoryKeyOfSection("galgame")).as("传入大类 key 不映射到自身").isNull();
        assertThat(TagConstants.categoryKeyOfSection("gg-type-unknown")).isNull();
        assertThat(TagConstants.categoryKeyOfSection(" g-walkthrough ")).isNull();
    }

    // ── categories() ─────────────────────

    @Test
    void categories返回四个大类且顺序稳定() {
        assertThat(TagConstants.categories()).hasSize(4);
        assertThat(TagConstants.categories()).extracting(Category::key)
                .containsExactly("galgame", "technique", "others", "galgame-resource");
        assertThat(TagConstants.categories()).extracting(Category::label)
                .containsExactly("Galgame", "技术交流", "其它话题", "Galgame 资源");
    }

    @Test
    void categories每次都返回同一实例() {
        assertThat(TagConstants.categories()).isSameAs(TagConstants.categories());
    }

    @Test
    void categories及其小分支列表不可变() {
        List<Category> categories = TagConstants.categories();
        assertThatThrownBy(() -> categories.add(categories.get(0)))
                .as("常量表不应被调用方修改")
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> categories.get(0).sections().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void 每个大类都有非空的key和label以及小分支() {
        assertThat(TagConstants.categories()).allSatisfy(c -> {
            assertThat(c.key()).as("大类 key").isNotBlank();
            assertThat(c.label()).as("大类 label").isNotBlank();
            assertThat(c.description()).as("大类 description").isNotBlank();
            assertThat(c.sections()).as("大类 %s 的小分支", c.key()).isNotEmpty();
            assertThat(c.sections()).allSatisfy(s -> {
                assertThat(s.key()).isNotBlank();
                assertThat(s.label()).isNotBlank();
            });
        });
    }

    // ── 三个「其它」小分支（历史易混点）─────────

    @Test
    void galgame资源下三个others小分支key各不相同但label都是其它() {
        List<Section> others = sectionsOf("galgame-resource").stream()
                .filter(s -> s.key().endsWith("-others"))
                .toList();

        assertThat(others).extracting(Section::key)
                .containsExactly("gg-type-others", "gg-lang-others", "gg-plat-others");
        assertThat(others).extracting(Section::key)
                .as("三个 key 必须互不相同——历史上易混淆的点")
                .doesNotHaveDuplicates();
        assertThat(others).extracting(Section::label)
                .as("label 相同是数据本来的样子，key 才是唯一键")
                .containsExactly("其它", "其它", "其它");
        assertThat(others).extracting(Section::key)
                .allSatisfy(k -> assertThat(TagConstants.isSectionKey(k)).isTrue());
        assertThat(others).extracting(Section::key)
                .allSatisfy(k -> assertThat(TagConstants.categoryKeyOfSection(k)).isEqualTo("galgame-resource"));
    }

    @Test
    void 全表label为其它的小分支共六个且key互不相同() {
        List<Section> others = allSections().stream()
                .filter(s -> "其它".equals(s.label()))
                .toList();

        assertThat(others).extracting(Section::key)
                .containsExactlyInAnyOrder(
                        "g-other", "t-other", "o-other",
                        "gg-type-others", "gg-lang-others", "gg-plat-others");
        assertThat(others).extracting(Section::key)
                .as("label 会重复，key 不会")
                .doesNotHaveDuplicates();
        assertThat(others).extracting(Section::label)
                .containsOnly("其它");
    }

    // ── 全表一致性 ───────────────────────

    @Test
    void 所有小分支key全局唯一() {
        List<String> keys = allSections().stream().map(Section::key).toList();
        assertThat(keys).doesNotHaveDuplicates();
        assertThat(keys).hasSize(51);
    }

    @Test
    void 每个小分支都能被识别且映射回所属大类() {
        for (Category c : TagConstants.categories()) {
            for (Section s : c.sections()) {
                assertThat(TagConstants.isSectionKey(s.key()))
                        .as("isSectionKey(%s)", s.key()).isTrue();
                assertThat(TagConstants.categoryKeyOfSection(s.key()))
                        .as("categoryKeyOfSection(%s)", s.key()).isEqualTo(c.key());
                assertThat(TagConstants.isCategoryKey(s.key()))
                        .as("小分支不应被当作大类: %s", s.key()).isFalse();
            }
        }
    }

    @Test
    void 大类key都不是小分支key() {
        for (Category c : TagConstants.categories()) {
            assertThat(TagConstants.isSectionKey(c.key())).as("大类 %s", c.key()).isFalse();
            assertThat(TagConstants.categoryKeyOfSection(c.key())).as("大类 %s", c.key()).isNull();
        }
    }

    @Test
    void 小分支key前缀与所属大类一致() {
        Map<String, String> prefixByCategory = Map.of(
                "galgame", "g-",
                "technique", "t-",
                "others", "o-",
                "galgame-resource", "gg-");

        for (Category c : TagConstants.categories()) {
            String prefix = prefixByCategory.get(c.key());
            for (Section s : c.sections()) {
                assertThat(s.key())
                        .as("大类 %s 的 key 应以 %s 开头", c.key(), prefix)
                        .startsWith(prefix);
            }
        }
    }

    @Test
    void 大类与小分支数量() {
        assertThat(sectionsOf("galgame")).hasSize(7);
        assertThat(sectionsOf("technique")).hasSize(11);
        assertThat(sectionsOf("others")).hasSize(9);
        assertThat(sectionsOf("galgame-resource")).as("类型 8 + 语言 5 + 平台 6 + 作品分类 5").hasSize(24);
    }

    @Test
    void galgame资源小分支按类型语言平台作品分组() {
        List<Section> res = sectionsOf("galgame-resource");
        assertThat(res.stream().filter(s -> s.key().startsWith("gg-type-")).count()).isEqualTo(8);
        assertThat(res.stream().filter(s -> s.key().startsWith("gg-lang-")).count()).isEqualTo(5);
        assertThat(res.stream().filter(s -> s.key().startsWith("gg-plat-")).count()).isEqualTo(6);
        assertThat(res.stream().filter(s -> s.key().startsWith("gg-work-")).count()).isEqualTo(5);
    }
}
