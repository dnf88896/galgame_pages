// =====================================================================
// galgameTag.js —— Galgame 标签系统共享常量（前后端一致）
// 参考设计契约 §2 category 枚举与颜色
// =====================================================================

// category → 中文（7 枚举，前后端一致）
export const CATEGORY_LABELS = {
  type: '资源类型',
  language: '语言',
  platform: '平台',
  content: '游戏内容',
  meta: '作品属性',
  technical: '技术细节',
  sexual: '成人内容',
}

// category → 主色（type 蓝 / language 灰 / platform 绿 / content 蓝 /
// meta+technical 绿 / sexual 红）；详情页资源 chips 与标签区 chip 用
export const CATEGORY_COLOR = {
  type: 'primary', // 蓝
  language: 'info', // 灰
  platform: 'success', // 绿
  content: 'primary', // 蓝
  meta: 'success', // 绿
  technical: 'success', // 绿
  sexual: 'danger', // 红
}

// 新建标签 / 展示时的类别下拉选项（7 枚举）
export const CATEGORY_OPTIONS = Object.keys(CATEGORY_LABELS).map((key) => ({
  value: key,
  label: CATEGORY_LABELS[key],
}))

// spoiler_level → 中文角标
export const SPOILER_LABELS = {
  0: '无剧透',
  1: '轻微剧透',
  2: '严重剧透',
}

// 详情页标签区类别筛选 chip 组：全部 / 内容 / 属性 / 技术 / 成人
// （key 'all' 表示不筛选；其余对应 category）
export const TAG_SECTION_FILTERS = [
  { key: 'all', label: '全部' },
  { key: 'content', label: '内容' },
  { key: 'meta', label: '属性' },
  { key: 'technical', label: '技术' },
  { key: 'sexual', label: '成人' },
]

// 资源类 category（详情信息卡 meta 区资源 chips 行展示，标签区不显示）
export const RESOURCE_CATEGORIES = ['type', 'language', 'platform']

// 标签区展示的 category（资源类之外）
export const TAG_SECTION_CATEGORIES = ['content', 'meta', 'technical', 'sexual']

// 标签库页按此顺序分组展示
export const TAG_LIBRARY_CATEGORY_ORDER = ['type', 'language', 'platform', 'content', 'meta', 'technical', 'sexual']
