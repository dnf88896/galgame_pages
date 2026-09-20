// 返回上一页。
// 用法：模板里 @back="() => goBack(router)"，需要指定兜底页时 @back="() => goBack(router, '/galgame')"。
//
// ⚠️ 判断「能不能后退」必须看 Vue Router 写在 history.state.back 里的站内上一页路径，
// **不能**用 window.history.length——那是整个浏览器标签页的历史长度，跨站导航和刷新都不会
// 重置，用户只要在本标签页点过任何一个链接它就恒 > 1，兜底分支形同虚设；反过来在新标签页
// 直接粘 URL 打开时它又可能是 1，让兜底分支真的执行。
//
// ⚠️ 兜底必须用 replace 而不是 push：push 会把当前页写进新记录的 state.back，于是
// 「本来无处可退」的页面凭空多出一条可退记录，下次点返回就真的 back 回来，和兜底目标页
// 形成 A↔B 来回跳的死循环。SectionView 的 /tag/:category 与 /tag/:category/:section
// 共用同一个组件，曾因此无限往返（点返回到对面、再点又回来）。
export function goBack(router, fallback = '/') {
  if (hasInternalBack()) {
    router.back()
  } else {
    router.replace(fallback)
  }
}

// history.state.back 为 Vue Router 4（HTML5 模式）写入的站内上一页路径；
// 直接打开、刷新后首次进入、或从外站跳进来时为 null —— 此时 history.back() 会退出本站，
// 所以必须走兜底。读不到 state 时保守按「没有站内历史」处理，保证不会退到站外。
function hasInternalBack() {
  return !!(window.history.state && window.history.state.back)
}
