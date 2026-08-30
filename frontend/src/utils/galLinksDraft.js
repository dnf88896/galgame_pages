// 制作人员/角色编辑器与主表单之间的跨路由草稿传递：
// 主表单点「修改制作人员/角色」跳转前 saveDraft，编辑器「完成」改草稿里对应 links 再 saveDraft，
// 返回后主表单 onMounted 里 loadDraft 恢复（含名称/标签/简介等未保存内容 + 编辑模式标记）。
// 用 sessionStorage：同一标签页内有效，刷新也不丢；关闭标签页自动清理。
const KEY = 'gal_links_draft_v1'

// draft 结构：{ form, editMode, imagePreview, links, relatedOptions }
// form: 主表单完整对象（含 staffLinks/characterLinks）；editMode: 详情页是否处于编辑模式
export function saveDraft(draft) {
  try {
    sessionStorage.setItem(KEY, JSON.stringify(draft))
  } catch (e) {
    // 序列化失败（如超大表单）静默忽略，不阻断跳转
  }
}

export function loadDraft() {
  try {
    const raw = sessionStorage.getItem(KEY)
    return raw ? JSON.parse(raw) : null
  } catch (e) {
    return null
  }
}

export function clearDraft() {
  try {
    sessionStorage.removeItem(KEY)
  } catch (e) {
    // 忽略
  }
}
