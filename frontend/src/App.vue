<template>
  <div class="app-root">
    <header class="nav-bar">
      <div class="nav-inner">
        <router-link to="/" class="nav-brand">KUN Gal Forum</router-link>
        <nav class="nav-right">
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
              <span class="nav-username">{{ user.username }}</span>
            </router-link>
            <el-button link type="danger" :loading="loggingOut" @click="logout">退出</el-button>
          </template>
        </nav>
      </div>
    </header>
    <main class="app-main">
      <router-view />
    </main>
  </div>
</template>

<script setup>
import { computed, ref, onMounted, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import api from './api'
import { token, user, clearToken } from './store/user'
import { unreadRefreshKey } from './store/unread'
import { resolveAssetUrl } from './utils/format'

const router = useRouter()

const loggedIn = computed(() => !!token.value && !!user.value)
const avatarSrc = computed(() => resolveAssetUrl(user.value?.avatar_url))
const firstChar = computed(() => (user.value?.username || '?').slice(0, 1).toUpperCase())

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

onMounted(() => loadUnread())

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
</style>
