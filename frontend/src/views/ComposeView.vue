<template>
  <div class="page compose-page">
    <div class="compose-head">
      <el-button link type="primary" @click="router.back()">← 返回</el-button>
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
          <el-input
            v-model="form.content"
            type="textarea"
            :rows="8"
            placeholder="写下你的想法、推荐、求助或资源说明"
            maxlength="2000"
            show-word-limit
          />
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
import { token } from '../store/user'
import { categories, formatSize, getErrorMessage, fetchTagStructure } from '../utils/format'

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

  submitting.value = true
  formStatus.value = '正在发布...'
  formStatusError.value = false
  try {
    const { data } = await api.post('/posts', fd)
    ElMessage.success('发布成功')
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
  align-items: center;
  gap: 12px;
}
.form-status {
  font-size: 13px;
  color: #67c23a;
}
.form-status.error {
  color: #f56c6c;
}
</style>
