package com.galgame.constants;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 标签常量（kungal 开源仓库原样，禁止改字）：三大类 + 小分支标签。
 * <p>大类顺序即书写顺序，输出 /api/tags 时按此顺序返回；
 * 小分支 key 以 g-/t-/o- 前缀区分所属大类。
 */
public final class TagConstants {

    /** 大类标签（含其下小分支） */
    public record Category(String key, String label, String description, List<Section> sections) {
    }

    /** 小分支标签 */
    public record Section(String key, String label) {
    }

    private static final List<Section> GALGAME_SECTIONS = List.of(
            new Section("g-walkthrough", "攻略"),
            new Section("g-chatting", "闲聊"),
            new Section("g-article", "文章"),
            new Section("g-seeking", "寻求资源"),
            new Section("g-news", "资讯"),
            new Section("g-releases", "新作消息"),
            new Section("g-other", "其它"));

    private static final List<Section> TECHNIQUE_SECTIONS = List.of(
            new Section("t-crack", "逆向工程"),
            new Section("t-web", "Web"),
            new Section("t-languages", "编程语言"),
            new Section("t-help", "请求帮助"),
            new Section("t-linux", "Linux"),
            new Section("t-practical", "实用技术"),
            new Section("t-ai", "AI"),
            new Section("t-android", "Android"),
            new Section("t-adobe", "Adobe"),
            new Section("t-algorithm", "算法"),
            new Section("t-other", "其它"));

    private static final List<Section> OTHERS_SECTIONS = List.of(
            new Section("o-anime", "动漫"),
            new Section("o-comics", "漫画"),
            new Section("o-music", "音乐"),
            new Section("o-novel", "轻小说"),
            new Section("o-daily", "日常"),
            new Section("o-essay", "个人随笔"),
            new Section("o-forum", "论坛相关"),
            new Section("o-patch", "补丁网站"),
            new Section("o-other", "其它"));

    /** 三大类，顺序即输出顺序 */
    private static final List<Category> CATEGORIES = List.of(
            new Category("galgame", "Galgame",
                    "Galgame 相关的话题, 攻略, 闲聊, 长文, 寻求资源, 游戏疑难杂症求助, 新作消息, 资讯, 本地化, 逆向等等",
                    GALGAME_SECTIONS),
            new Category("technique", "技术交流",
                    "技术相关的话题, Web 开发, 编程语言, 寻求帮助, Linux, 实用技术, AI, 逆向工程, Android, Adobe, 算法等等",
                    TECHNIQUE_SECTIONS),
            new Category("others", "其它话题",
                    "其它话题, 动漫, 漫画, 音乐, 轻小说, 日常, 个人随笔, 论坛相关, 鲲 Galgame 补丁网站相关, 其它内容",
                    OTHERS_SECTIONS));

    /** key → 大类，用于 categoryKey 存在性判断 */
    private static final Map<String, Category> CATEGORY_BY_KEY = new LinkedHashMap<>();
    /** sectionKey → categoryKey（全量小分支 key 表，用于存在性判断） */
    private static final Map<String, String> CATEGORY_KEY_BY_SECTION = new LinkedHashMap<>();

    static {
        for (Category c : CATEGORIES) {
            CATEGORY_BY_KEY.put(c.key(), c);
            for (Section s : c.sections()) {
                CATEGORY_KEY_BY_SECTION.put(s.key(), c.key());
            }
        }
    }

    private TagConstants() {
    }

    /** 三大类有序列表（含小分支），供 /api/tags 输出 */
    public static List<Category> categories() {
        return CATEGORIES;
    }

    /** sectionKey → 所属 category key（由前缀 g-/t-/o- 决定），未知返回 null */
    public static String categoryKeyOfSection(String sectionKey) {
        if (sectionKey == null || sectionKey.isEmpty()) {
            return null;
        }
        return switch (sectionKey.charAt(0)) {
            case 'g' -> "galgame";
            case 't' -> "technique";
            case 'o' -> "others";
            default -> null;
        };
    }

    /** sectionKey 是否为已知小分支标签 */
    public static boolean isSectionKey(String sectionKey) {
        return sectionKey != null && CATEGORY_KEY_BY_SECTION.containsKey(sectionKey);
    }

    /** categoryKey 是否为三大类之一 */
    public static boolean isCategoryKey(String categoryKey) {
        return categoryKey != null && CATEGORY_BY_KEY.containsKey(categoryKey);
    }
}
