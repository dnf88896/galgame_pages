<template>
  <div class="page messages-page">
    <el-page-header content="消息" @back="$router.push('/')" />

    <el-tabs v-model="activeTab" style="margin-top: 12px" @tab-change="onTabChange">
      <el-tab-pane label="私信" name="dm">
        <el-card v-if="loading && !conversations.length" style="margin-top: 16px">
          <el-skeleton :rows="6" animated />
        </el-card>

        <el-alert
          v-else-if="loadError"
          :title="loadError"
          type="error"
          :closable="false"
          style="margin-top: 16px"
        />

        <el-empty
          v-else-if="!conversations.length"
          style="margin-top: 32px"
          description="还没有私聊，去别人主页点『私聊』开始吧"
        />

        <div v-else class="conv-list">
          <div v-for="item in conversations" :key="item.user.id" class="conv-item" @click="goChat(item.user.id)">
            <el-avatar v-if="avatarSrcOf(item.user)" :src="avatarSrcOf(item.user)" :size="44" />
            <el-avatar v-else :size="44" class="avatar-text">{{ firstCharOf(item.user) }}</el-avatar>
            <div class="conv-main">
              <div class="conv-top">
                <span class="conv-name">{{ item.user.username }}</span>
                <span v-if="lastTimeOf(item)" class="conv-time">{{ lastTimeOf(item) }}</span>
              </div>
              <div class="conv-preview">{{ lastPreviewOf(item) }}</div>
            </div>
            <el-badge
              v-if="Number(item.unread) > 0"
              :value="Number(item.unread)"
              :max="99"
              class="conv-badge"
            />
          </div>
        </div>
      </el-tab-pane>

      <el-tab-pane name="notifications">
        <template #label>
          <el-badge :value="notifUnread" :hidden="!notifUnread" :max="99" class="notif-tab-badge">通知</el-badge>
        </template>
        <el-card v-if="notifLoading && !notifications.length" style="margin-top: 16px">
          <el-skeleton :rows="6" animated />
        </el-card>

        <el-alert
          v-else-if="notifError"
          :title="notifError"
          type="error"
          :closable="false"
          style="margin-top: 16px"
        />

        <el-empty
          v-else-if="!notifLoaded || !notifications.length"
          style="margin-top: 32px"
          description="还没有通知"
        />

        <div v-else class="notif-list">
          <div
            v-for="n in notifications"
            :key="n.id"
            class="notif-item"
            :class="{ unread: Number(n.is_read) === 0, clickable: n.post_id != null && n.post_id !== '' }"
            @click="goNotification(n)"
          >
            <el-avatar v-if="notifAvatarSrc(n)" :src="notifAvatarSrc(n)" :size="40" />
            <el-avatar v-else :size="40" class="avatar-text">{{ notifFirstChar(n) }}</el-avatar>
            <div class="notif-main">
              <div class="notif-top">
                <span class="notif-title">{{ notifTitle(n) }}</span>
                <span class="notif-time">{{ formatTime(n.created_at) }}</span>
              </div>
              <div v-if="notifPreview(n)" class="notif-preview">{{ notifPreview(n) }}</div>
              <div v-if="notifMedia(n).length" class="notif-media" @click.stop>
                <template v-for="m in notifMedia(n)" :key="m.url">
                  <img v-if="m.kind === 'image'" :src="resolveAssetUrl(m.url)" class="notif-media-img" alt="" />
                  <audio v-else-if="m.kind === 'audio'" :src="resolveAssetUrl(m.url)" controls class="notif-media-audio" />
                  <video v-else-if="m.kind === 'video'" :src="resolveAssetUrl(m.url)" controls class="notif-media-video" />
                </template>
              </div>
            </div>
            <span v-if="Number(n.is_read) === 0" class="notif-dot" />
          </div>
        </div>
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import api from '../api'
import { user, requireLogin } from '../store/user'
import { bumpUnreadRefresh } from '../store/unread'
import { formatTime, resolveAssetUrl, getErrorMessage } from '../utils/format'

const router = useRouter()

const activeTab = ref('dm')

const conversations = ref([])
const loading = ref(false)
const loadError = ref('')

async function load() {
  loading.value = true
  loadError.value = ''
  try {
    const { data } = await api.get('/dm/conversations')
    conversations.value = Array.isArray(data) ? data : []
  } catch (e) {
    loadError.value = getErrorMessage(e, '加载会话列表失败')
  } finally {
    loading.value = false
  }
}

function goChat(id) {
  router.push(`/messages/${id}`)
}

function avatarSrcOf(u) {
  return resolveAssetUrl(u?.avatar_url)
}

function firstCharOf(u) {
  return (u?.username || '?').slice(0, 1).toUpperCase()
}

function lastTimeOf(item) {
  return item.last_message ? formatTime(item.last_message.created_at) : ''
}

// 最后一条消息预览：自己的消息加「我: 」前缀；无消息给占位文案
function lastPreviewOf(item) {
  const lm = item.last_message
  if (!lm) return '还没有消息'
  return Number(lm.sender_id) === Number(user.value?.id) ? `我: ${lm.content}` : lm.content
}

// ---- 通知 tab ----
const notifications = ref([])
const notifLoading = ref(false)
const notifError = ref('')
const notifLoaded = ref(false)
// 通知 tab 自身的未读数（顶栏红点 = 私信未读 + 通知未读；这里只负责「通知」tab 的角标）
const notifUnread = ref(0)

// 拉取通知未读数（不产生已读副作用，进入消息页即调用，用于通知 tab 角标）
async function loadNotifUnread() {
  try {
    const { data } = await api.get('/notifications/unread-count')
    notifUnread.value = data && data.unread != null ? Number(data.unread) : 0
  } catch {
    notifUnread.value = 0
  }
}

async function loadNotifications() {
  notifLoading.value = true
  notifError.value = ''
  try {
    const { data } = await api.get('/notifications')
    notifications.value = Array.isArray(data) ? data : []
  } catch (e) {
    notifications.value = []
    notifError.value = getErrorMessage(e, '加载通知失败')
  } finally {
    notifLoading.value = false
    notifLoaded.value = true
  }
}

// 切到「通知」tab：拉取列表 → 全部标已读 → 通知 tab 角标清零 → 触发顶栏红点刷新
async function onTabChange(name) {
  if (name !== 'notifications') return
  await loadNotifications()
  try {
    await api.post('/notifications/read')
    // 本地同步已读状态，未读圆点/背景立即消失
    notifications.value.forEach((n) => {
      n.is_read = 1
    })
    notifUnread.value = 0
  } catch {
    // 标已读失败不阻塞列表展示
  }
  bumpUnreadRefresh()
}

// 通知标题按 type 通用化（mention / announcement / report / warning / 其他兜底），勿写死只认 mention
function notifTitle(n) {
  if (n.type === 'announcement') return n.title || '公告'
  if (n.type === 'report' || n.type === 'warning') return n.title || (n.type === 'report' ? '举报受理结果' : '内容被删除')
  const name = n.actor?.username || '有人'
  if (n.type === 'mention') return `${name} 提到你`
  return `${name} 有新消息`
}

// 正文摘要：前 ~80 字
function notifPreview(n) {
  const c = n.content || ''
  return c.length > 80 ? `${c.slice(0, 80)}…` : c
}

// 通知媒体附件解析：n.media 为 JSON 数组字符串（[{url, mime}]），按 mime 前缀归类 kind，只保留有 url 的项
function notifMedia(n) {
  if (!n.media) return []
  let list
  try {
    list = JSON.parse(n.media)
  } catch {
    return []
  }
  if (!Array.isArray(list)) return []
  return list
    .map((m) => {
      const mime = m?.mime || ''
      let kind = ''
      if (mime.startsWith('image/')) kind = 'image'
      else if (mime.startsWith('audio/')) kind = 'audio'
      else if (mime.startsWith('video/')) kind = 'video'
      return { url: m?.url || '', kind }
    })
    .filter((m) => m.url && m.kind)
}

function notifAvatarSrc(n) {
  return resolveAssetUrl(n.actor?.avatar_url)
}

function notifFirstChar(n) {
  return (n.actor?.username || '?').slice(0, 1).toUpperCase()
}

// 有 post_id 才允许跳转帖子详情
function goNotification(n) {
  const pid = n.post_id
  if (pid == null || pid === '') return
  const id = Number(pid)
  if (Number.isInteger(id) && id > 0) router.push(`/post/${id}`)
}

onMounted(() => {
  if (!requireLogin(router)) return
  load()
  loadNotifUnread()
})
</script>

<style scoped>
.messages-page {
  max-width: 720px;
}
.conv-list {
  margin-top: 16px;
}
.conv-item {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 14px;
  background: #fff;
  border: 1px solid #e4e7ed;
  border-radius: 8px;
  margin-bottom: 12px;
  cursor: pointer;
  transition: box-shadow 0.2s;
}
.conv-item:hover {
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.06);
}
.avatar-text {
  background: #409eff;
  color: #fff;
  font-weight: 600;
  flex-shrink: 0;
}
.conv-main {
  flex: 1;
  min-width: 0;
}
.conv-top {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 8px;
}
.conv-name {
  font-size: 15px;
  font-weight: 600;
  color: #303133;
}
.conv-time {
  font-size: 12px;
  color: #999;
  white-space: nowrap;
}
.conv-preview {
  margin-top: 4px;
  font-size: 13px;
  color: #909399;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.conv-badge {
  flex-shrink: 0;
}

/* ---- 通知 ---- */
.notif-list {
  margin-top: 16px;
}
.notif-tab-badge :deep(.el-badge__content) {
  position: static;
  transform: none;
  margin-left: 5px;
  vertical-align: 2px;
}
.notif-item {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 14px;
  background: #fff;
  border: 1px solid #e4e7ed;
  border-radius: 8px;
  margin-bottom: 12px;
  transition: box-shadow 0.2s;
}
.notif-item.clickable {
  cursor: pointer;
}
.notif-item.clickable:hover {
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.06);
}
.notif-item.unread {
  background: #f0f7ff;
}
.notif-main {
  flex: 1;
  min-width: 0;
}
.notif-top {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 8px;
}
.notif-title {
  font-size: 15px;
  font-weight: 600;
  color: #303133;
}
.notif-time {
  font-size: 12px;
  color: #999;
  white-space: nowrap;
}
.notif-preview {
  margin-top: 4px;
  font-size: 13px;
  color: #909399;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.notif-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: #f56c6c;
  flex-shrink: 0;
}
.notif-media {
  display: flex;
  flex-direction: column;
  align-items: flex-start; /* 关键：阻止 img/video 被 flex stretch 拉伸变形 */
  gap: 8px;
  margin-top: 8px;
}
.notif-media-img {
  display: block;
  width: auto;
  height: auto;
  max-width: 100%;
  max-height: 240px;
  object-fit: contain; /* 兜底：即使宽高被强制，内容也等比不拉伸 */
  border-radius: 6px;
}
.notif-media-audio {
  width: 100%;
}
.notif-media-video {
  display: block;
  width: auto;
  height: auto;
  max-width: 100%;
  max-height: 320px;
  object-fit: contain;
  border-radius: 6px;
}
</style>
