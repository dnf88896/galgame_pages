<template>
  <div class="app-root">
    <header class="nav-bar">
      <div class="nav-inner">
        <router-link to="/" class="nav-brand">KUN Gal Forum</router-link>
        <nav class="nav-right">
          <router-link
            v-if="user && Number(user.admin_level) > 0"
            to="/admin/reports"
            class="nav-report-btn"
          >举报受理</router-link>
          <button class="nav-gear" type="button" title="设置" @click="settingsOpen = true">
            <svg xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 24 24" stroke-width="1.5" stroke="currentColor" width="18" height="18">
              <path stroke-linecap="round" stroke-linejoin="round" d="M10.343 3.94c.09-.542.56-.94 1.11-.94h1.093c.55 0 1.02.398 1.11.94l.149.894c.07.424.384.764.78.93.398.164.855.142 1.205-.108l.737-.527a1.125 1.125 0 0 1 1.45.12l.773.774c.39.389.44 1.002.12 1.45l-.527.737c-.25.35-.272.806-.108 1.205.164.397.505.71.93.78l.893.15c.543.09.94.56.94 1.109v1.094c0 .55-.397 1.02-.94 1.11l-.893.149c-.425.07-.765.383-.93.78-.165.398-.143.854.108 1.205l.527.737c.32.448.27 1.06-.12 1.45l-.774.774a1.125 1.125 0 0 1-1.449.12l-.738-.527c-.35-.25-.806-.272-1.203-.107-.397.165-.71.505-.781.929l-.149.894c-.09.542-.56.94-1.11.94h-1.094c-.55 0-1.019-.398-1.11-.94l-.148-.894c-.071-.424-.384-.764-.781-.93-.398-.164-.854-.142-1.204.108l-.738.527a1.125 1.125 0 0 1-1.45-.12l-.773-.774a1.125 1.125 0 0 1-.12-1.45l.527-.737c.25-.35.272-.806.108-1.205-.164-.397-.505-.71-.93-.78l-.894-.15c-.542-.09-.94-.56-.94-1.109v-1.094c0-.55.398-1.02.94-1.11l.894-.149c.424-.07.765-.383.93-.78.165-.398.143-.854-.108-1.205l-.526-.737a1.125 1.125 0 0 1 .12-1.45l.773-.773a1.125 1.125 0 0 1 1.45-.12l.737.527c.35.25.807.272 1.204.107.397-.165.71-.505.781-.929l.149-.894Z"/>
              <path stroke-linecap="round" stroke-linejoin="round" d="M15 12a3 3 0 1 1-6 0 3 3 0 0 1 6 0Z"/>
            </svg>
          </button>
          <template v-if="!loggedIn">
            <router-link to="/login" class="nav-link">登录 / 注册</router-link>
          </template>
          <template v-else>
            <el-badge :value="unreadTotal" :hidden="!unreadTotal" :max="99">
              <router-link to="/messages" class="nav-link">消息</router-link>
            </el-badge>
            <router-link :to="`/user/${user.id}`" class="nav-user">
              <el-avatar v-if="user.avatar_url" :src="avatarSrc" :size="28" />
              <el-avatar v-else :size="28" class="nav-avatar-text">{{ firstChar }}</el-avatar>
              <span class="nav-username">{{ user.nickname || user.username }}</span>
            </router-link>
            <el-button link type="danger" :loading="loggingOut" @click="logout">退出</el-button>
          </template>
        </nav>
      </div>
    </header>
    <main class="app-main">
      <router-view />
    </main>
    <SettingsPanel v-model="settingsOpen" />

    <!-- 萌点获得提示：屏幕中心「+n萌点」淡出 -->
    <MoeToast />

    <!-- 封禁锁屏：固定全屏覆盖，唯一操作是退出登录 -->
    <div v-if="banned" class="ban-overlay">
      <div class="ban-box">
        <h1 class="ban-title">您已被封禁</h1>
        <p class="ban-sub">{{ banUntilText }}</p>
        <el-button type="danger" plain size="small" @click="logout">退出登录</el-button>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, ref, onMounted, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import api from './api'
import { token, user, setUser, clearToken } from './store/user'
import { unreadRefreshKey } from './store/unread'
import { resolveAssetUrl } from './utils/format'
import SettingsPanel from './components/SettingsPanel.vue'
import MoeToast from './components/MoeToast.vue'
import { refreshMoe } from './utils/moeGain'

const router = useRouter()

// 设置面板开关（登录与否都可打开）
const settingsOpen = ref(false)

const loggedIn = computed(() => !!token.value && !!user.value)
const avatarSrc = computed(() => resolveAssetUrl(user.value?.avatar_url))
const firstChar = computed(() => (user.value?.nickname || user.value?.username || '?').slice(0, 1).toUpperCase())

// 封禁判断：ban_until 存在且未过期
const banned = computed(() => {
  const until = user.value?.ban_until
  if (!until) return false
  const t = new Date(until).getTime()
  return !isNaN(t) && t > Date.now()
})
const banPermanent = computed(() => !!user.value?.ban_until && user.value.ban_until.startsWith('2099'))
const banUntilText = computed(() => {
  if (banPermanent.value) return '永久封禁'
  const until = user.value?.ban_until
  if (!until) return ''
  const d = new Date(until)
  return isNaN(d.getTime()) ? '封禁中' : `封禁至 ${d.toLocaleString()}`
})

const loggingOut = ref(false)

// 顶栏「消息」入口的未读总数 = 私聊未读（各会话 unread 之和）+ 通知未读
const unreadTotal = ref(0)

// 拉取未读总数；并行拉私聊 + 通知，任一接口失败按 0 处理，不打扰用户
async function loadUnread() {
  if (!loggedIn.value) {
    unreadTotal.value = 0
    return
  }
  const [convData, notifData] = await Promise.all([
    api.get('/dm/conversations').then(({ data }) => data).catch(() => []),
    api.get('/notifications/unread-count').then(({ data }) => data).catch(() => null),
  ])
  const convList = Array.isArray(convData) ? convData : []
  const dmUnread = convList.reduce((sum, item) => sum + (Number(item.unread) || 0), 0)
  const notifUnread = notifData ? Number(notifData.unread) || 0 : 0
  unreadTotal.value = dmUnread + notifUnread
}

// 登录时刷新当前用户信息（让中途被封禁的用户刷新页面后立即锁屏）
async function refreshUser() {
  if (!loggedIn.value) return
  try {
    const { data } = await api.get('/auth/me')
    if (data && data.id) {
      setUser(data)
      // 建立/更新萌点基线（首次不提示避免历史萌点误报；之后加分点靠 refreshMoe 检测增量弹「+n萌点」）
      refreshMoe(data?.moe_points)
    }
  } catch {
    // 401 由 api 拦截器统一处理；其他错误静默
  }
}

async function logout() {
  loggingOut.value = true
  try {
    await api.post('/auth/logout')
  } catch {
    // 退出接口失败也继续本地登出
  }
  clearToken()
  unreadTotal.value = 0
  ElMessage.success('已退出登录')
  router.push('/')
  loggingOut.value = false
}

onMounted(() => {
  refreshUser()
  loadUnread()
})

// 回到会话列表/聊天页后未读会变化，命中 /messages 路径时刷新
watch(
  () => router.currentRoute.value.path,
  (path) => {
    if (path === '/messages' || path.startsWith('/messages/')) loadUnread()
  },
)

// 登录后拉取一次；退出登录清零
watch(loggedIn, (val) => {
  if (val) loadUnread()
  else unreadTotal.value = 0
})

// 其他页面（如消息页通知 tab 标已读后）通过 bumpUnreadRefresh() 触发顶栏红点刷新
watch(unreadRefreshKey, () => loadUnread())
</script>

<style scoped>
.nav-bar {
  background: #fff;
  border-bottom: 1px solid #e4e7ed;
  position: sticky;
  top: 0;
  z-index: 10;
}
.nav-inner {
  max-width: 960px;
  margin: 0 auto;
  padding: 0 16px;
  height: 52px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
}
.nav-brand {
  font-size: 18px;
  font-weight: 700;
  color: #303133;
  text-decoration: none;
  white-space: nowrap;
}
.nav-brand:hover {
  color: #409eff;
}
.nav-right {
  display: flex;
  align-items: center;
  gap: 12px;
}
.nav-link {
  color: #409eff;
  text-decoration: none;
  font-size: 14px;
}
.nav-link:hover {
  text-decoration: underline;
}
.nav-report-btn {
  color: #fff;
  background: #f56c6c;
  border-radius: 6px;
  padding: 4px 12px;
  font-size: 13px;
  text-decoration: none;
  transition: background 0.2s;
}
.nav-report-btn:hover {
  background: #f78989;
}
.nav-gear {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  padding: 0;
  background: transparent;
  border: none;
  border-radius: 6px;
  color: #606266;
  cursor: pointer;
  transition: color 0.2s, background 0.2s;
}
.nav-gear:hover {
  color: #409eff;
  background: rgba(64, 158, 255, 0.1);
}
.nav-user {
  display: flex;
  align-items: center;
  gap: 8px;
  text-decoration: none;
  color: #303133;
}
.nav-avatar-text {
  background: #409eff;
  color: #fff;
  font-weight: 600;
}
.nav-username {
  font-size: 14px;
  font-weight: 500;
}
.app-main {
  min-height: calc(100vh - 52px);
}
.ban-overlay {
  position: fixed;
  inset: 0;
  z-index: 9999;
  background: #fff;
  display: flex;
  align-items: center;
  justify-content: center;
}
.ban-box {
  text-align: center;
}
.ban-title {
  font-size: 34px;
  font-weight: 700;
  color: #f56c6c;
  margin: 0 0 12px;
  letter-spacing: 6px;
}
.ban-sub {
  font-size: 14px;
  color: #909399;
  margin: 0 0 20px;
}
</style>
