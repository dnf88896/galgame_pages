<template>
  <div class="page detail">
    <el-page-header content="帖子详情" @back="$router.push('/')" />

    <div v-if="invalidId" class="status-box">
      <p class="note">缺少有效的帖子 ID。</p>
    </div>

    <template v-else>
      <el-card v-if="loading && !post" style="margin-top: 16px">
        <el-skeleton :rows="5" animated />
      </el-card>

      <el-empty
        v-else-if="notFound"
        style="margin-top: 32px"
        description="帖子不存在或已被删除。"
      />

      <el-alert
        v-else-if="loadError"
        :title="loadError"
        type="error"
        :closable="false"
        style="margin-top: 16px"
      />

      <template v-else-if="post">
        <el-card class="post-card" style="margin-top: 16px">
          <div class="post-top">
            <el-tag size="small" type="primary" effect="light">{{ post.category }}</el-tag>
            <el-tag v-for="k in (post.tags || [])" :key="k" size="small" type="primary" effect="light">#{{ tagLabel(k) }}</el-tag>
            <span class="author">
              <router-link v-if="post.user_id" :to="`/user/${post.user_id}`" class="author-link">
                {{ post.author }}
              </router-link>
              <span v-else>{{ post.author }}</span>
            </span>
            <span class="dot">·</span>
            <span class="time">{{ formatTime(post.created_at) }}</span>
          </div>
          <h2 class="post-title">{{ post.title }}</h2>
          <p class="content">{{ post.content }}</p>
          <AttachmentList :attachments="post.attachments" />
          <div class="post-actions">
            <button
              class="like-button"
              :class="{ liked: post.liked }"
              type="button"
              :disabled="postLiking"
              @click="togglePostLike"
            >
              {{ post.liked ? '♥' : '♡' }} {{ post.like_count }}
            </button>
            <span class="stat-chip">回复 {{ post.reply_count }}</span>
            <span class="stat-chip">浏览 {{ post.view_count }}</span>
            <el-button
              v-if="isOwner"
              type="danger"
              plain
              size="small"
              class="delete-button"
              :loading="deleting"
              @click="deletePost"
            >
              删除
            </el-button>
          </div>
        </el-card>

        <el-card class="replies-card" style="margin-top: 16px">
          <template #header>
            <span class="replies-title">
              回复
              <span v-if="post.replies.length">({{ post.replies.length }})</span>
            </span>
          </template>

          <div v-if="!post.replies.length" class="reply-empty">
            还没有回复，来抢沙发吧。
          </div>
          <div v-else class="reply-list">
            <div v-for="r in post.replies" :key="r.id" class="reply">
              <div class="reply-top">
                <span class="reply-author">
                  <router-link v-if="r.user_id" :to="`/user/${r.user_id}`" class="author-link">
                    {{ r.author }}
                  </router-link>
                  <span v-else>{{ r.author }}</span>
                </span>
                <span class="dot">·</span>
                <span class="time">{{ formatTime(r.created_at) }}</span>
              </div>
              <div v-if="parentNameOf(r)" class="reply-parent">回复 @{{ parentNameOf(r) }}</div>
              <p class="reply-content">{{ r.content }}</p>
              <div class="reply-actions">
                <button
                  class="like-button small"
                  :class="{ liked: r.liked }"
                  type="button"
                  :disabled="replyLiking.has(r.id)"
                  @click="toggleReplyLike(r)"
                >
                  {{ r.liked ? '♥' : '♡' }} {{ r.like_count }}
                </button>
                <el-button link size="small" @click="replyingTo = r">回复</el-button>
                <el-button
                  v-if="user && Number(r.user_id) === Number(user.id)"
                  link
                  type="danger"
                  size="small"
                  class="reply-delete-button"
                  @click="deleteReply(r)"
                >
                  删除
                </el-button>
              </div>
            </div>
          </div>

          <el-divider />

          <div class="reply-form">
            <template v-if="!loggedIn">
              <div class="login-prompt">
                <p>登录后即可回复，参与讨论。</p>
                <el-button type="primary" @click="goLogin">去登录</el-button>
              </div>
            </template>
            <template v-else>
              <div v-if="replyingTo" class="replying-to">
                <span class="replying-label">回复 @{{ replyingTo.author }}</span>
                <el-button link size="small" @click="replyingTo = null">取消</el-button>
              </div>
              <el-input
                v-model="replyForm.content"
                type="textarea"
                :rows="4"
                maxlength="2000"
                show-word-limit
                placeholder="写下你的回复"
              />
              <div class="reply-form-actions">
                <el-button type="primary" :loading="replySubmitting" @click="submitReply">
                  发表回复
                </el-button>
                <span class="form-status" :class="{ error: replyStatusError }">{{ replyStatus }}</span>
              </div>
            </template>
          </div>
        </el-card>
      </template>
    </template>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import api from '../api'
import AttachmentList from '../components/AttachmentList.vue'
import { token, user, requireLogin } from '../store/user'
import { fetchTagStructure, sectionLabel, formatTime, getErrorMessage } from '../utils/format'

const route = useRoute()
const router = useRouter()

const postId = computed(() => Number(route.params.id))
const invalidId = computed(() => !Number.isInteger(postId.value) || postId.value <= 0)

const post = ref(null)
const loading = ref(false)
const notFound = ref(false)
const loadError = ref('')

// 标签结构（来自 /api/tags），用于把 post.tags 的小分支 key 转成中文 label
const tagCategories = ref([])

async function loadTagStructure() {
  tagCategories.value = await fetchTagStructure()
}

// 标签显示为 #label（如 #攻略），查不到则回退到原始 key
function tagLabel(k) {
  return sectionLabel(tagCategories.value, k) || k
}

const loggedIn = computed(() => !!token.value)

// 当前登录用户是否为该帖作者（决定是否显示删除入口）
const isOwner = computed(
  () =>
    !!user.value &&
    !!post.value &&
    post.value.user_id != null &&
    Number(post.value.user_id) === Number(user.value.id)
)
const deleting = ref(false)

const postLiking = ref(false)
const replyLiking = reactive(new Set())

const replyForm = reactive({ content: '' })
const replySubmitting = ref(false)
const replyStatus = ref('')
const replyStatusError = ref(false)

// 正在回复的目标回复对象（null 表示普通回复）
const replyingTo = ref(null)

// silent：静默刷新（如回复成功后整页重拉详情），不闪烁骨架屏
async function load({ silent = false } = {}) {
  if (invalidId.value) return
  if (!silent) loading.value = true
  notFound.value = false
  loadError.value = ''
  try {
    const { data } = await api.get(`/posts/${postId.value}`)
    post.value = data
  } catch (e) {
    if (e.response?.status === 404) {
      notFound.value = true
      post.value = null
    } else {
      loadError.value = getErrorMessage(e, '加载帖子失败')
    }
  } finally {
    if (!silent) loading.value = false
  }
}

function goLogin() {
  router.push({ path: '/login', query: { redirect: router.currentRoute.value.fullPath } })
}

async function togglePostLike() {
  if (!requireLogin(router)) return
  if (!post.value || postLiking.value) return
  postLiking.value = true
  try {
    const { data } = await api.post(`/posts/${post.value.id}/like`)
    // 点赞数直接用服务端返回的新计数
    post.value.liked = data.liked
    post.value.like_count = data.like_count
  } catch (e) {
    ElMessage.error(getErrorMessage(e, '点赞失败'))
  } finally {
    postLiking.value = false
  }
}

async function toggleReplyLike(reply) {
  if (!requireLogin(router)) return
  if (replyLiking.has(reply.id)) return
  replyLiking.add(reply.id)
  try {
    const { data } = await api.post(`/replies/${reply.id}/like`)
    reply.liked = data.liked
    reply.like_count = data.like_count
  } catch (e) {
    ElMessage.error(getErrorMessage(e, '点赞失败'))
  } finally {
    replyLiking.delete(reply.id)
  }
}

// 回复的父回复对象（回复列表是平铺数组，按 parent_id 在 post.replies 中查找），找不到返回 null
function parentOf(r) {
  if (r.parent_id == null || r.parent_id === '') return null
  return (post.value?.replies || []).find((x) => Number(x.id) === Number(r.parent_id)) || null
}

// 父回复作者名：优先取列表中的父回复，其次用创建时快照的 parent_author（父评论被删后引用仍显示）
// 父回复作者名：优先取列表中的父回复；父评论被删除后 parent_id 会置 NULL，
// 此时不能靠 parent_id 判断是否嵌套，改由创建时的 parent_author 快照兜底
function parentNameOf(r) {
  return parentOf(r)?.author || r.parent_author || ''
}

async function submitReply() {
  const content = replyForm.content.trim()
  if (!content) {
    replyStatus.value = '回复内容不能为空。'
    replyStatusError.value = true
    return
  }

  replySubmitting.value = true
  replyStatus.value = '正在发表回复...'
  replyStatusError.value = false
  try {
    const body = { content }
    if (replyingTo.value) body.parent_id = replyingTo.value.id
    await api.post(`/posts/${postId.value}/replies`, body)
    replyForm.content = ''
    replyingTo.value = null
    replyStatus.value = ''
    ElMessage.success('回复已发表')
    await load({ silent: true })
  } catch (e) {
    const msg = getErrorMessage(e, '发表回复失败')
    replyStatus.value = msg
    replyStatusError.value = true
    ElMessage.error(msg)
  } finally {
    replySubmitting.value = false
  }
}

async function deletePost() {
  try {
    await ElMessageBox.confirm(
      '删除后不可恢复，确定要删除这篇帖子吗？',
      '删除帖子',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' }
    )
  } catch {
    return // 用户取消
  }
  deleting.value = true
  try {
    await api.delete(`/posts/${post.value.id}`)
    ElMessage.success('帖子已删除')
    router.push('/')
  } catch (e) {
    ElMessage.error(getErrorMessage(e, '删除失败'))
  } finally {
    deleting.value = false
  }
}

// 删除自己的回复（仅作者本人可见该入口）
async function deleteReply(reply) {
  try {
    await ElMessageBox.confirm(
      '确定删除这条回复吗？删除后不可恢复。',
      '删除回复',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' }
    )
  } catch {
    return // 用户取消
  }
  try {
    await api.delete(`/replies/${reply.id}`)
    ElMessage.success('回复已删除')
    if (replyingTo.value && Number(replyingTo.value.id) === Number(reply.id)) {
      replyingTo.value = null
    }
    // 静默刷新帖子详情，同步 reply_count / 剩余回复
    await load({ silent: true })
  } catch (e) {
    ElMessage.error(getErrorMessage(e, '删除回复失败'))
  }
}

watch(
  () => route.params.id,
  () => {
    post.value = null
    notFound.value = false
    loadError.value = ''
    replyingTo.value = null
    load()
  },
)

onMounted(() => {
  load()
  loadTagStructure()
})
</script>

<style scoped>
.page.detail {
  max-width: 760px;
}
.post-top {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
  color: #666;
  margin-bottom: 10px;
}
.author {
  color: #333;
  font-weight: 500;
}
.author-link {
  color: #409eff;
  text-decoration: none;
}
.author-link:hover {
  text-decoration: underline;
}
.dot {
  color: #ccc;
}
.time {
  color: #999;
}
.post-title {
  font-size: 20px;
  margin: 0 0 12px;
}
.content {
  white-space: pre-wrap;
  line-height: 1.7;
  color: #303133;
  margin: 0 0 16px;
}
.post-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 16px;
}
.like-button {
  border: 1px solid #dcdfe6;
  background: #fff;
  color: #606266;
  border-radius: 999px;
  padding: 6px 16px;
  font-size: 14px;
  line-height: 1;
  cursor: pointer;
  transition: all 0.2s;
}
.like-button:hover {
  color: #f56c9a;
  border-color: #f56c9a;
}
.like-button.liked {
  color: #f56c9a;
  border-color: #f56c9a;
  background: #fdf2f7;
}
.like-button:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}
.like-button.small {
  padding: 3px 10px;
  font-size: 12px;
}
.stat-chip {
  color: #888;
  background: #f5f7fa;
  border-radius: 999px;
  padding: 2px 10px;
  font-size: 12px;
}
.delete-button {
  margin-left: auto;
}
.replies-title {
  font-size: 15px;
}
.reply-empty {
  color: #909399;
  text-align: center;
  padding: 24px 0;
}
.reply {
  padding: 12px 0;
  border-bottom: 1px solid #f0f0f0;
}
.reply:last-child {
  border-bottom: none;
}
.reply-top {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
  color: #666;
  margin-bottom: 6px;
}
.reply-author {
  color: #333;
  font-weight: 500;
}
.reply-content {
  white-space: pre-wrap;
  line-height: 1.6;
  color: #303133;
  margin: 0 0 8px;
}
.reply-parent {
  margin: 0 0 4px;
  font-size: 13px;
  color: #909399;
}
.reply-actions {
  display: flex;
  align-items: center;
  gap: 12px;
}
.reply-form {
  margin-top: 8px;
}
.replying-to {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
  font-size: 13px;
  color: #606266;
}
.replying-label {
  background: #f0f7ff;
  color: #409eff;
  border-radius: 4px;
  padding: 2px 8px;
}
.login-prompt {
  text-align: center;
  padding: 16px 0;
  color: #606266;
}
.login-prompt p {
  margin: 0 0 12px;
}
.reply-form-actions {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-top: 12px;
}
.form-status {
  font-size: 13px;
  color: #67c23a;
}
.form-status.error {
  color: #f56c6c;
}
.status-box {
  margin-top: 32px;
  text-align: center;
  color: #909399;
}
</style>
