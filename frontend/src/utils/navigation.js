// 返回上一页：有后退记录则回退到上一页；否则（用户直接打开本页、无历史记录）兜底回首页。
// 用法：模板里 @back="() => goBack(router)"（router 由 useRouter() 提供）。
export function goBack(router) {
  if (window.history.length > 1) {
    router.back()
  } else {
    router.push('/')
  }
}
