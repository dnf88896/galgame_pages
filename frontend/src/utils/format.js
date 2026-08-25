import api from '../api'

// 论坛分区（顺序即默认展示顺序，默认第一个）
export const categories = [
  '话题',
  'galgame',
  '全站动态',
  'gal情报',
  'gal资源',
  '资源和求助',
  '其他',
]

// 各分区的一句话简介，首页左侧分区栏展示（与 categories 一一对应）
export const categoryDescriptions = {
  话题: '社区讨论、日常交流与作品杂谈',
  galgame: '作品、会社、角色、路线与音乐',
  全站动态: '公告、更新、活跃内容与站务',
  gal情报: '新作消息、体验版、发售与汉化进度',
  gal资源: '资源站点、补丁、工具与资料整理',
  资源和求助: '寻找资源、运行问题与入坑咨询',
  其他: '不属于以上分类的内容',
}

// created_at（如 2026-08-24T12:00:00）→ 显示到分钟
export function formatTime(value) {
  if (!value) return ''
  const date = new Date(String(value).replace(' ', 'T'))
  if (Number.isNaN(date.getTime())) return String(value).slice(0, 16)
  const p = (n) => String(n).padStart(2, '0')
  return `${date.getFullYear()}-${p(date.getMonth() + 1)}-${p(date.getDate())} ${p(date.getHours())}:${p(date.getMinutes())}`
}

export function formatSize(bytes) {
  if (bytes == null || Number.isNaN(bytes)) return ''
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  if (bytes < 1024 * 1024 * 1024) return `${(bytes / 1024 / 1024).toFixed(1)} MB`
  return `${(bytes / 1024 / 1024 / 1024).toFixed(1)} GB`
}

// 判断 created_at 是否属于今天
export function isToday(value) {
  if (!value) return false
  const d = new Date(String(value).replace(' ', 'T'))
  if (Number.isNaN(d.getTime())) return false
  const now = new Date()
  return (
    d.getFullYear() === now.getFullYear() &&
    d.getMonth() === now.getMonth() &&
    d.getDate() === now.getDate()
  )
}

// 附件 url 形如 /uploads/post_1/xxx.png，为跨端口开发（前端 5173 / 后端 8080）
// 相对路径一律补上后端 origin；已是绝对地址则原样返回。
export function resolveAssetUrl(url) {
  if (!url) return ''
  if (/^https?:\/\//i.test(url)) return url
  const base = (api.defaults.baseURL || 'http://localhost:8080/api').replace(/\/api\/?$/, '')
  return `${base}${url.startsWith('/') ? url : `/${url}`}`
}

// 统一从错误对象提取后端 error 信息
export function getErrorMessage(e, fallback = '请求失败') {
  return e?.response?.data?.error || e?.message || fallback
}

// 拉取标签结构：GET /tags → { categories: [{ key, label, description, post_count, sections: [{key,label,post_count}] }] }
// 返回 data.categories 数组；失败兜底返回空数组，避免影响页面主流程。
export async function fetchTagStructure() {
  try {
    const { data } = await api.get('/tags')
    return Array.isArray(data?.categories) ? data.categories : []
  } catch (e) {
    return []
  }
}

// 根据大类 key 在标签结构中查找中文 label，查不到返回 ''
export function categoryLabel(categories, key) {
  if (!Array.isArray(categories) || !key) return ''
  const c = categories.find((item) => item && item.key === key)
  return c?.label || ''
}

// 根据小分支 key 在所有大类下查找中文 label，查不到返回 ''
export function sectionLabel(categories, key) {
  if (!Array.isArray(categories) || !key) return ''
  for (const c of categories) {
    if (!Array.isArray(c?.sections)) continue
    const s = c.sections.find((item) => item && item.key === key)
    if (s?.label) return s.label
  }
  return ''
}
