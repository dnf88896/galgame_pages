<template>
  <div class="page compose-page">
    <div class="compose-head">
      <el-button link type="primary" @click="() => goBack(router)">← 返回</el-button>
      <h1 class="compose-title">发布帖子</h1>
    </div>

    <el-card class="compose-card">
      <div v-if="!loggedIn" class="login-prompt">
        <p>登录后即可发帖，参与论坛讨论。</p>
        <el-button type="primary" @click="goLogin">去登录</el-button>
      </div>
      <el-form v-else :model="form" label-position="top">
        <el-form-item label="分区" class="compose-category">
          <el-select v-model="form.category" class="w-full">
            <el-option v-for="c in categories" :key="c" :label="c" :value="c" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="tagModeAvailable" label="标签（可选）" class="compose-category">
          <el-select v-model="form.sections" multiple collapse-tags class="w-full" placeholder="选择标签（可选，可多选）" clearable>
            <el-option-group v-for="c in tagStructure" :key="c.key" :label="c.label">
              <el-option v-for="s in c.sections" :key="s.key" :label="s.label" :value="s.key" />
            </el-option-group>
          </el-select>
        </el-form-item>
        <el-form-item label="标题">
          <el-input
            ref="titleInputRef"
            v-model="form.title"
            placeholder="写一个帖子标题"
            maxlength="80"
            @keyup.enter="submit"
          />
        </el-form-item>
        <el-form-item label="正文">
          <MentionTextarea
            v-model="form.content"
            :rows="8"
            :maxlength="2000"
            show-word-limit
            placeholder="写下你的想法、推荐、求助或资源说明"
          />
          <div class="compose-emoji-bar">
            <el-button
              class="emoji-toggle"
              :class="{ active: emojiVisible }"
              type="text"
              @click="emojiVisible = !emojiVisible"
            >😀 表情</el-button>
            <EmojiPicker
              v-if="emojiVisible"
              class="compose-emoji-picker"
              @pick="onEmoji"
            />
          </div>
        </el-form-item>

        <el-form-item label="封面图（可选）">
          <input type="file" accept="image/*" @change="onCoverChange" />
          <div v-if="coverFile" class="cover-picked">
            <img :src="coverPreview" class="cover-preview" alt="封面预览" />
            <el-button link type="danger" @click="removeCover">移除封面</el-button>
          </div>
        </el-form-item>

        <el-form-item label="附件">
          <input type="file" multiple @change="onFileChange" />
          <div v-if="attachmentQueue.length" class="attachments-picked">
            <div class="attachments-picked-head">
              <span>已选附件（{{ attachmentQueue.length }}）</span>
              <el-button link type="primary" @click="clearAttachments">清空</el-button>
            </div>
            <div class="picked-list">
              <div v-for="(f, i) in attachmentQueue" :key="i" class="picked-item">
                <span class="picked-name" :title="f.name">{{ f.name }}</span>
                <span class="picked-size">{{ formatSize(f.size) }}</span>
                <el-button
                  link
                  type="danger"
                  :aria-label="`移除 ${f.name}`"
                  @click="removeAttachment(i)"
                >×</el-button>
              </div>
            </div>
          </div>
        </el-form-item>

        <div class="compose-actions">
          <el-button type="primary" :loading="submitting" @click="submit">发表</el-button>
          <el-button @click="saveDraft">保存草稿</el-button>
          <el-button v-if="hasDraft" link type="danger" @click="clearDraft">清除草稿</el-button>
          <span class="form-status" :class="{ error: formStatusError }">{{ formStatus }}</span>
        </div>
      </el-form>
    </el-card>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import api from '../api'
import EmojiPicker from '../components/EmojiPicker.vue'
import MentionTextarea from '../components/MentionTextarea.vue'
import { goBack } from '../utils/navigation'
import { token, user } from '../store/user'
import { categories, formatSize, getErrorMessage, fetchTagStructure } from '../utils/format'
import { refreshMoe } from '../utils/moeGain'

const route = useRoute()
const router = useRouter()

const loggedIn = computed(() => !!token.value)

const form = reactive({
  category: categories[0],
  sections: [],
  title: '',
  content: '',
})
const submitting = ref(false)
const formStatus = ref('')
const formStatusError = ref(false)
const attachmentQueue = ref([])
const titleInputRef = ref(null)

// 封面图：单张图片，提交时随 multipart 一起发 cover 字段（可选）
const coverFile = ref(null)
const coverPreview = ref('')
const emojiVisible = ref(false)

function onCoverChange(e) {
  const f = e.target.files && e.target.files[0]
  if (f) {
    coverFile.value = f
    coverPreview.value = URL.createObjectURL(f)
  }
  e.target.value = ''
}

function removeCover() {
  if (coverPreview.value) URL.revokeObjectURL(coverPreview.value)
  coverFile.value = null
  coverPreview.value = ''
}

// 点选 emoji 追加到正文末尾（面板保持展开，可连续插入）
function onEmoji(e) {
  form.content += e
}

// 标签结构：GET /api/tags 返回 { categories: [{ key, label, sections: [{key,label}] }] }
// 分区（categories 常量）与标签（tagStructure）相互独立，不做联动
const tagStructure = ref([])

// 标签下拉是否可用：fetchTagStructure 成功且拿到标签时启用
const tagModeAvailable = computed(() => tagStructure.value.length > 0)

function goLogin() {
  router.push({ path: '/login', query: { redirect: route.fullPath } })
}

function onFileChange(e) {
  const files = Array.from(e.target.files || [])
  if (files.length) {
    attachmentQueue.value.push(...files)
  }
  e.target.value = ''
}

function removeAttachment(index) {
  attachmentQueue.value.splice(index, 1)
}

function clearAttachments() {
  attachmentQueue.value = []
}

// 发帖草稿：localStorage 按用户 id 隔离，只存文字字段（分区/标签/标题/正文），附件/封面不存（浏览器无法存文件）
const DRAFT_KEY = 'galforum_post_draft'
const draftKey = () => `${DRAFT_KEY}_${user.value?.id}`
const hasDraft = ref(false)

// 保存当前表单为草稿（可随时覆盖；附件/封面已选时提示不会随草稿保存）
function saveDraft() {
  if (!loggedIn.value) return
  localStorage.setItem(
    draftKey(),
    JSON.stringify({
      category: form.category,
      sections: form.sections,
      title: form.title,
      content: form.content,
    }),
  )
  hasDraft.value = true
  if (attachmentQueue.value.length || coverFile.value) {
    ElMessage.success('草稿已保存（附件和封面不会随草稿保存，请发帖前重新选择）')
  } else {
    ElMessage.success('草稿已保存')
  }
}

// 恢复草稿：进入发帖页且已登录时调用，把上次保存的草稿填充进表单
function loadDraft() {
  if (!loggedIn.value) return
  try {
    const raw = localStorage.getItem(draftKey())
    if (!raw) return
    const d = JSON.parse(raw)
    if (!d || typeof d !== 'object') return
    if (categories.includes(d.category)) form.category = d.category
    if (Array.isArray(d.sections)) form.sections = d.sections
    if (typeof d.title === 'string') form.title = d.title
    if (typeof d.content === 'string') form.content = d.content
    hasDraft.value = true
    ElMessage.info('已恢复上次保存的草稿')
  } catch {
    // 草稿损坏（JSON 解析失败）静默忽略，不打断发帖页
  }
}

// 清除草稿（silent=true 时只删不提示，用于发帖成功后静默清理）
function clearDraft({ silent = false } = {}) {
  localStorage.removeItem(draftKey())
  hasDraft.value = false
  if (!silent) ElMessage.success('草稿已清除')
}

async function submit() {
  const title = form.title.trim()
  const content = form.content.trim()
  if (!title) {
    formStatus.value = '标题不能为空。'
    formStatusError.value = true
    return
  }
  if (!content) {
    formStatus.value = '正文不能为空。'
    formStatusError.value = true
    return
  }

  const fd = new FormData()
  // 分区与标签相互独立：分区必选（默认第一个板块），标签可选
  fd.append('category', form.category || categories[0])
  for (const k of form.sections) fd.append('sections', k)
  fd.append('title', title)
  fd.append('content', content)
  for (const f of attachmentQueue.value) {
    fd.append('attachments', f, f.name)
  }
  if (coverFile.value) {
    fd.append('cover', coverFile.value, coverFile.value.name)
  }

  submitting.value = true
  formStatus.value = '正在发布...'
  formStatusError.value = false
  try {
    const { data } = await api.post('/posts', fd)
    if (coverPreview.value) URL.revokeObjectURL(coverPreview.value)
    ElMessage.success('发布成功')
    clearDraft({ silent: true }) // 发帖成功清除草稿
    // 每日首次发帖 +10 萌点：refreshMoe 检测增量弹「+n萌点」并同步（非首次不加分则不弹）
    refreshMoe()
    // 发布成功直接进入帖子详情页
    router.push(`/post/${data.id}`)
  } catch (e) {
    const msg = getErrorMessage(e, '发布失败')
    formStatus.value = msg
    formStatusError.value = true
    ElMessage.error(msg)
  } finally {
    submitting.value = false
  }
}

onMounted(async () => {
  loadDraft() // 已登录且有草稿时恢复上次未填完的表单
  try {
    const result = await fetchTagStructure()
    const cats = Array.isArray(result) ? result : result?.categories || []
    if (cats.length) {
      tagStructure.value = cats
    }
  } catch {
    tagStructure.value = []
  }
  // 分区默认选第一个板块（categories[0]）
  if (loggedIn.value) titleInputRef.value?.focus()
})
</script>

<style scoped>
.page.compose-page {
  max-width: 720px;
}
.compose-head {
  display: flex;
  align-items: center;
  gap: 12px;
  margin: 16px 0;
}
.compose-title {
  margin: 0;
  font-size: 22px;
}
.compose-card {
  margin-bottom: 16px;
}
.compose-category {
  width: 220px;
}
.w-full {
  width: 100%;
}
.login-prompt {
  text-align: center;
  padding: 24px 0;
  color: #606266;
}
.login-prompt p {
  margin: 0 0 12px;
}
.attachments-picked {
  margin-top: 8px;
  width: 100%;
}
.attachments-picked-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-size: 13px;
  color: #666;
  margin-bottom: 6px;
}
.picked-list {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
.picked-item {
  display: flex;
  align-items: center;
  gap: 6px;
  background: #f5f7fa;
  border-radius: 6px;
  padding: 4px 8px;
  font-size: 13px;
}
.picked-name {
  max-width: 200px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.picked-size {
  color: #888;
}
.compose-actions {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px 12px;
}
.form-status {
  font-size: 13px;
  color: #67c23a;
}
.form-status.error {
  color: #f56c6c;
}
/* 正文 emoji 开关 + 面板 */
.compose-emoji-bar {
  margin-top: 8px;
}
.compose-emoji-picker {
  margin-top: 8px;
}
.emoji-toggle {
  font-size: 14px;
  line-height: 1;
  padding: 4px 6px;
}
.emoji-toggle.active {
  color: #409eff;
}
/* 封面图预览 */
.cover-picked {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px 12px;
  margin-top: 8px;
  width: 100%;
}
.cover-preview {
  display: block;
  max-width: 260px;
  max-height: 160px;
  object-fit: contain;
  border-radius: 6px;
  border: 1px solid #e4e7ed;
  background: #f5f7fa;
}
</style>
