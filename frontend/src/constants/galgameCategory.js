// =====================================================================
// galgameCategory.js —— Galgame 分类系统共享常量（前端展示 / 筛选 / 编辑）
// 旧的 24 个硬编码 gg-* section_key，与「标签」系统彻底分离并存。
// label 严格取自后端 TagConstants.GALGAME_RESOURCE_SECTIONS（禁止改字）。
// =====================================================================

// 24 项分类：key = section_key，label = 后端中文 label，group = 分组（type/lang/plat/work）
export const GALGAME_CATEGORIES = [
  // 类型 type（8）
  { key: 'gg-type-game', label: '游戏本体', group: 'type' },
  { key: 'gg-type-patch', label: '补丁', group: 'type' },
  { key: 'gg-type-collection', label: '合集', group: 'type' },
  { key: 'gg-type-voice', label: '音声相关', group: 'type' },
  { key: 'gg-type-image', label: '图片相关', group: 'type' },
  { key: 'gg-type-ai', label: 'AI 相关', group: 'type' },
  { key: 'gg-type-video', label: '视频相关', group: 'type' },
  { key: 'gg-type-others', label: '其它', group: 'type' },
  // 语言 lang（5）
  { key: 'gg-lang-ja-jp', label: '日语', group: 'lang' },
  { key: 'gg-lang-en-us', label: '英语', group: 'lang' },
  { key: 'gg-lang-zh-cn', label: '简体中文', group: 'lang' },
  { key: 'gg-lang-zh-tw', label: '繁体中文', group: 'lang' },
  { key: 'gg-lang-others', label: '其它', group: 'lang' },
  // 平台 plat（6）
  { key: 'gg-plat-windows', label: 'Windows', group: 'plat' },
  { key: 'gg-plat-mac', label: 'macOS', group: 'plat' },
  { key: 'gg-plat-linux', label: 'Linux', group: 'plat' },
  { key: 'gg-plat-emulator', label: '模拟器', group: 'plat' },
  { key: 'gg-plat-app', label: '应用直装', group: 'plat' },
  { key: 'gg-plat-others', label: '其它', group: 'plat' },
  // 作品分类 work（5）
  { key: 'gg-work-ba-saku', label: '拔作', group: 'work' },
  { key: 'gg-work-plot', label: '剧情作', group: 'work' },
  { key: 'gg-work-moe', label: '萌系', group: 'work' },
  { key: 'gg-work-daily', label: '日常系', group: 'work' },
  { key: 'gg-work-unclassified', label: '未分类', group: 'work' },
]

// 四组分类分组（key 对应 GALGAME_CATEGORIES 的 group）
export const CATEGORY_GROUPS = [
  { key: 'type', name: '类型', allLabel: '全部类型' },
  { key: 'lang', name: '语言', allLabel: '全部语言' },
  { key: 'plat', name: '平台', allLabel: '全部平台' },
  { key: 'work', name: '作品分类', allLabel: '全部作品分类' },
]

// section_key → 中文 label；查不到返回原 key
export function categoryLabel(key) {
  const c = GALGAME_CATEGORIES.find((x) => x.key === key)
  return c ? c.label : key
}
