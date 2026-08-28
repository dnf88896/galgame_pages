<template>
  <div class="page chat-page">
    <div class="chat-header">
      <el-button link type="primary" @click="router.push('/messages')">返回</el-button>
      <router-link v-if="peer" :to="`/user/${userId}`" class="chat-user">
        <el-avatar v-if="peer.avatar_url" :src="avatarSrc" :size="32" />
        <el-avatar v-else :size="32" class="avatar-text">{{ firstChar }}</el-avatar>
        <span class="chat-username">{{ peer.nickname || peer.username }}</span>
      </router-link>
      <span v-else class="chat-title">私聊</span>
    </div>

    <el-card v-if="loading && !messages.length" style="margin-top: 16px">
      <el-skeleton :rows="6" animated />
    </el-card>

    <el-alert
      v-else-if="loadError"
      :title="loadError"
      type="error"
      :closable="false"
      style="margin-top: 16px"
    />

    <el-empty v-else-if="notFound" style="margin-top: 32px" description="用户不存在。" />

    <template v-else>
      <el-alert
        v-if="blockedByMe"
        title="你已屏蔽对方"
        type="error"
        :closable="false"
        style="margin-top: 16px"
      />
      <el-alert
        v-else-if="blockedByThem"
        title="对方已屏蔽你"
        type="warning"
        :closable="false"
        style="margin-top: 16px"
      />

      <div ref="listRef" class="msg-list">
        <div v-if="!messages.length" class="msg-empty">还没有消息，打个招呼吧</div>
        <div
          v-for="m in messages"
          :key="m.id"
          class="msg-row"
          :class="{ mine: Number(m.sender_id) === myId }"
        >
          <div class="bubble" :class="{ recalled: m.is_recalled }">{{ m.is_recalled ? '已撤回' : m.content }}</div>
          <div class="msg-meta">
            <el-button
              v-if="canRecall(m)"
              link
              type="info"
              size="small"
              class="recall-btn"
              @click="recall(m)"
            >撤回</el-button>
            <span class="msg-time">{{ formatTime(m.created_at) }}</span>
          </div>
        </div>
      </div>

      <div v-if="!(blockedByMe || blockedByThem)" class="chat-input">
        <div class="chat-input-main">
          <div class="chat-emoji-bar">
            <el-button
              class="emoji-toggle"
              :class="{ active: emojiVisible }"
              type="text"
              @click="emojiVisible = !emojiVisible"
            >😀</el-button>
            <EmojiPicker
              v-if="emojiVisible"
              class="chat-emoji-picker"
              @pick="onEmoji"
            />
          </div>
          <el-input
            v-model="draft"
            type="textarea"
            :rows="2"
            maxlength="2000"
            show-word-limit
            placeholder="输入消息，回车发送"
            @keydown.enter.exact.prevent="send"
          />
        </div>
        <el-button type="primary" :loading="sending" @click="send">发送</el-button>
      </div>
    </template>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onBeforeUnmount, watch, nextTick } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import api from '../api'
import EmojiPicker from '../components/EmojiPicker.vue'
import { user as currentUser, requireLogin } from '../store/user'
import { formatTime, resolveAssetUrl, getErrorMessage } from '../utils/format'

const route = useRoute()
const router = useRouter()

const userId = computed(() => Number(route.params.userId))
const peer = ref(null)
const messages = ref([])
const loading = ref(false)
const notFound = ref(false)
const loadError = ref('')

const draft = ref('')
const sending = ref(false)

// 输入区 emoji 面板显隐；点选追加到草稿末尾
const emojiVisible = ref(false)

function onEmoji(e) {
  draft.value += e
}

// 两分钟内可撤回：自己发的、未撤回、且创建至今不超过 2 分钟
const RECALL_WINDOW_MS = 2 * 60 * 1000

function canRecall(m) {
  if (m.is_recalled || Number(m.sender_id) !== myId.value) return false
  if (!m.created_at) return false
  return Date.now() - new Date(m.created_at).getTime() <= RECALL_WINDOW_MS
}

// 撤回自己发的消息：只调接口，轮询会从服务器同步 is_recalled
async function recall(m) {
  try {
    const { data } = await api.post(`/dm/messages/${m.id}/recall`)
    Object.assign(m, data)
  } catch (e) {
    ElMessage.error(getErrorMessage(e, '撤回失败'))
  }
}

const blockedByMe = ref(false)
const blockedByThem = ref(false)

const listRef = ref(null)

const myId = computed(() => (currentUser.value ? Number(currentUser.value.id) : null))
const avatarSrc = computed(() => resolveAssetUrl(peer.value?.avatar_url))
const firstChar = computed(() => (peer.value?.nickname || peer.value?.username || '?').slice(0, 1).toUpperCase())

async function load() {
  const id = userId.value
  if (!Number.isInteger(id) || id <= 0) {
    notFound.value = true
    peer.value = null
    messages.value = []
    return
  }
  loading.value = true
  notFound.value = false
  loadError.value = ''
  try {
    const { data } = await api.get(`/dm/conversations/${id}`)
    if (data && data.user) {
      peer.value = data.user
      messages.value = Array.isArray(data.messages) ? data.messages : []
      blockedByMe.value = !!data.blocked_by_me
      blockedByThem.value = !!data.blocked_by_them
      await scrollToBottom()
    } else {
      // 后端未返回 user（对方不存在等）
      notFound.value = true
      peer.value = null
      messages.value = []
    }
  } catch (e) {
    if (e.response?.status === 404) {
      notFound.value = true
      peer.value = null
      messages.value = []
    } else {
      loadError.value = getErrorMessage(e, '加载会话失败')
    }
  } finally {
    loading.value = false
  }
}

async function scrollToBottom() {
  await nextTick()
  const el = listRef.value
  if (el) el.scrollTop = el.scrollHeight
}

async function send() {
  if (blockedByMe.value || blockedByThem.value) return
  const content = draft.value.trim()
  if (!content || sending.value) return
  sending.value = true
  try {
    const { data } = await api.post(`/dm/conversations/${userId.value}/messages`, { content })
    messages.value.push(data)
    draft.value = ''
    await scrollToBottom()
  } catch (e) {
    ElMessage.error(getErrorMessage(e, '发送失败'))
  } finally {
    sending.value = false
  }
}

// 每 4 秒静默重拉会话，拿到新消息时直接替换并滚到底（不闪烁）
let pollTimer = null

function startPolling() {
  stopPolling()
  pollTimer = setInterval(async () => {
    if (!Number.isInteger(userId.value) || userId.value <= 0) return
    try {
      const { data } = await api.get(`/dm/conversations/${userId.value}`)
      if (data && Array.isArray(data.messages)) {
        const prevLen = messages.value.length
        messages.value = data.messages
        blockedByMe.value = !!data.blocked_by_me
        blockedByThem.value = !!data.blocked_by_them
        if (data.messages.length > prevLen) await scrollToBottom()
      }
    } catch {
      // 轮询失败静默，不打扰用户
    }
  }, 4000)
}

function stopPolling() {
  if (pollTimer) {
    clearInterval(pollTimer)
    pollTimer = null
  }
}

watch(
  () => route.params.userId,
  () => {
    peer.value = null
    messages.value = []
    notFound.value = false
    loadError.value = ''
    blockedByMe.value = false
    blockedByThem.value = false
    load()
    startPolling()
  },
)

onMounted(() => {
  if (!requireLogin(router)) return
  load()
  startPolling()
})

onBeforeUnmount(stopPolling)
</script>

<style scoped>
.chat-page {
  max-width: 720px;
  margin: 0 auto;
  height: calc(100vh - 52px);
  padding: 0 16px;
  display: flex;
  flex-direction: column;
}
.chat-header {
  flex-shrink: 0;
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 10px 0;
  border-bottom: 1px solid #e4e7ed;
}
.chat-user {
  display: flex;
  align-items: center;
  gap: 8px;
  text-decoration: none;
  color: #303133;
}
.chat-user:hover {
  color: #409eff;
}
.chat-username {
  font-size: 15px;
  font-weight: 600;
}
.chat-title {
  font-size: 15px;
  color: #909399;
}
.avatar-text {
  background: #409eff;
  color: #fff;
  font-weight: 600;
}
.msg-list {
  flex: 1;
  overflow-y: auto;
  padding: 16px 0;
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.msg-empty {
  text-align: center;
  color: #909399;
  padding: 40px 0;
}
.msg-row {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  max-width: 72%;
}
.msg-row.mine {
  align-self: flex-end;
  align-items: flex-end;
}
.bubble {
  padding: 8px 12px;
  border-radius: 10px;
  background: #fff;
  border: 1px solid #e4e7ed;
  color: #303133;
  white-space: pre-wrap;
  word-break: break-word;
  line-height: 1.5;
  font-size: 14px;
}
.msg-row.mine .bubble {
  background: #409eff;
  border-color: #409eff;
  color: #fff;
}
/* 已撤回：灰色斜体占位，替代原文 */
.bubble.recalled {
  color: #909399;
  background: #f5f7fa;
  border-color: #f5f7fa;
  font-style: italic;
}
.msg-row.mine .bubble.recalled {
  color: #909399;
  background: #f0f2f5;
  border-color: #f0f2f5;
}
/* 气泡下方元信息行：时间 + 撤回按钮 */
.msg-meta {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 4px;
}
.msg-time {
  font-size: 12px;
  color: #999;
}
.recall-btn {
  padding: 0;
  font-size: 12px;
  line-height: 1;
}
.chat-input {
  flex-shrink: 0;
  display: flex;
  gap: 8px;
  align-items: flex-end;
  padding: 12px 0 16px;
  border-top: 1px solid #e4e7ed;
}
.chat-input-main {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.chat-emoji-picker {
  margin-top: 4px;
}
.emoji-toggle {
  font-size: 18px;
  line-height: 1;
  padding: 2px 4px;
}
.emoji-toggle.active {
  color: #409eff;
}
</style>
