import { ref } from 'vue'

// 未读刷新信号：bumpUnreadRefresh() 后 App.vue 会重新拉取顶栏未读总数
export const unreadRefreshKey = ref(0)
export function bumpUnreadRefresh() {
  unreadRefreshKey.value++
}
