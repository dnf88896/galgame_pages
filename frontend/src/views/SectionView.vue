<template>
  <div class="page section-page">
    <!-- ============ 大类页：无 section 参数 ============ -->
    <template v-if="!route.params.section">
      <el-page-header content="分区" @back="() => goBack(router)" />

      <div v-if="tagLoading" class="status-block">
        <el-skeleton :rows="4" animated />
      </div>

      <div v-else-if="currentCategory" class="category-panel">
        <div class="category-header">
          <h2 class="category-title">{{ currentCategory.label }}</h2>
          <p class="category-desc">{{ currentCategory.description }}</p>
          <span class="category-count">共 {{ currentCategory.post_count }} 帖</span>
        </div>
        <div class="section-grid">
          <router-link
            v-for="s in currentCategory.sections"
            :key="s.key"
            class="section-cell"
            :to="`/tag/${currentCategory.key}/${s.key}`"
          >
            <span class="section-cell-label">{{ s.label }}</span>
            <span class="section-cell-count">{{ s.post_count }}</span>
          </router-link>
        </div>
      </div>

      <el-empty v-else description="分类不存在" />
    </template>

    <!-- ============ 小分支页：有 section 参数 ============ -->
    <template v-else>
      <el-page-header @back="goBackToCategory">
        <template #content>
          <el-breadcrumb separator="/">
            <el-breadcrumb-item :to="`/tag/${route.params.category}`">{{ categoryName }}</el-breadcrumb-item>
            <el-breadcrumb-item>{{ sectionName }}</el-breadcrumb-item>
          </el-breadcrumb>
        </template>
      </el-page-header>

      <div v-if="tagLoading || postsLoading" class="post-list">
        <div v-for="i in 3" :key="i" class="post-card">
          <el-skeleton :rows="4" animated />
        </div>
      </div>

      <el-empty v-else-if="!currentCategory" description="分类不存在" />

      <el-alert
        v-else-if="postsError"
        :title="postsError"
        type="error"
        :closable="false"
        class="feed-error"
      />

      <div v-else class="post-list">
        <div class="section-header">
          <h2 class="section-title">{{ sectionName }}</h2>
          <span class="section-count">
            {{ currentSection ? currentSection.post_count : posts.length }} 帖
          </span>
        </div>

        <article v-for="p in posts" :key="p.id" class="post-card">
          <div class="post-top">
            <el-tag size="small" type="primary" effect="light">{{ postCategoryTag(p) }}</el-tag>
            <el-tag v-for="k in (p.tags || [])" :key="k" size="small" type="primary" effect="light">#{{ tagLabel(k) }}</el-tag>
            <span class="author">
              <router-link v-if="p.user_id" :to="`/user/${p.user_id}`" class="author-link">
                {{ p.author }}
              </router-link>
              <span v-else>{{ p.author }}</span>
            </span>
            <span class="dot">·</span>
            <span class="time">{{ formatTime(p.created_at) }}</span>
            <span class="pin-area">
              <span v-if="isPinned(p)" class="pinned-badge">置顶</span>
              <el-button
                v-if="isAdminUser"
                link
                type="warning"
                size="small"
                class="pin-btn"
                @click="togglePin(p)"
              >{{ isPinned(p) ? '取消置顶' : '置顶' }}</el-button>
            </span>
          </div>
          <h2 class="post-title" @click="goPost(p.id)">{{ p.title }}</h2>
          <p class="excerpt">{{ p.content }}</p>
          <AttachmentList :attachments="p.attachments" />
          <div class="post-bottom">
            <span class="stat-chip">回复 {{ p.reply_count }}</span>
            <span class="stat-chip">浏览 {{ p.view_count }}</span>
            <span class="stat-chip heart">♥ {{ p.like_count }}</span>
            <el-button link type="primary" class="enter-link" @click="goPost(p.id)">进入讨论</el-button>
          </div>
        </article>

        <el-empty v-if="!posts.length" description="该标签还没有帖子" />
      </div>
    </template>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import api from '../api'
import { goBack } from '../utils/navigation'
import { user } from '../store/user'
import AttachmentList from '../components/AttachmentList.vue'
import {
  fetchTagStructure,
  sectionLabel,
  formatTime,
  getErrorMessage,
  isPinned,
} from '../utils/format'

const route = useRoute()
const router = useRouter()

const tagCategories = ref([])
const tagLoading = ref(true)

const posts = ref([])
const postsLoading = ref(false)
const postsError = ref('')

// 当前大类：在 /api/tags 的三大类中按 key 匹配；未知则为 null
const currentCategory = computed(
  () => tagCategories.value.find((c) => c.key === route.params.category) || null,
)
// 当前小分支：大类下按 key 匹配；无 section 参数或未知则为 null
const currentSection = computed(() => {
  if (!currentCategory.value || !route.params.section) return null
  return (
    currentCategory.value.sections.find((s) => s.key === route.params.section) || null
  )
})

const categoryName = computed(
  () => currentCategory.value?.label || route.params.category || '',
)
const sectionName = computed(
  () =>
    currentSection.value?.label ||
    sectionLabel(tagCategories.value, route.params.section) ||
    route.params.section ||
    '',
)

async function loadTags() {
  tagLoading.value = true
  try {
    tagCategories.value = await fetchTagStructure()
  } finally {
    tagLoading.value = false
  }
}

// 小分支页：拉取挂了该标签的帖子列表（只按标签过滤，与分区无关）
async function loadPosts() {
  if (!route.params.section) return
  postsLoading.value = true
  postsError.value = ''
  try {
    const { data } = await api.get('/posts', { params: { section: route.params.section } })
    posts.value = data || []
  } catch (e) {
    postsError.value = getErrorMessage(e, '加载帖子失败')
  } finally {
    postsLoading.value = false
  }
}

function goBackToCategory() {
  router.push(`/tag/${route.params.category}`)
}
function goPost(id) {
  router.push(`/post/${id}`)
}

// 管理员才能看到帖子置顶/取消置顶入口
const isAdminUser = computed(() => !!user.value && Number(user.value.admin_level) > 0)

// 置顶/取消置顶：未置顶弹窗输入天数（正整数），已置顶确认后取消；成功后刷新列表
async function togglePin(p) {
  if (isPinned(p)) {
    try {
      await ElMessageBox.confirm('取消该帖的置顶？', '取消置顶', { type: 'warning' })
      await api.put(`/posts/${p.id}/pin`, { days: 0 })
      ElMessage.success('已取消置顶')
      await loadPosts()
    } catch (e) {
      if (typeof e === 'string') return // 用户取消弹窗
      ElMessage.error(getErrorMessage(e, '操作失败'))
    }
    return
  }
  try {
    const { value } = await ElMessageBox.prompt('请输入置顶天数', '置顶帖子', {
      inputPattern: /^[1-9]\d*$/,
      inputErrorMessage: '请输入正整数天数',
      inputValue: '1',
    })
    await api.put(`/posts/${p.id}/pin`, { days: Number(value) })
    ElMessage.success('已置顶')
    await loadPosts()
  } catch (e) {
    if (typeof e === 'string') return // 用户取消弹窗
    ElMessage.error(getErrorMessage(e, '操作失败'))
  }
}

// 帖子卡片分区标签：直接显示板块名（category），tags 标签显示 #label
function postCategoryTag(p) {
  return p.category || ''
}
function tagLabel(k) {
  return sectionLabel(tagCategories.value, k) || k
}

// 大类 / 小分支 参数切换时重新拉取帖子
watch(
  () => [route.params.category, route.params.section],
  () => {
    posts.value = []
    postsError.value = ''
    loadPosts()
  },
)

onMounted(async () => {
  await loadTags()
  loadPosts()
})
</script>

<style scoped>
.page.section-page {
  max-width: 1140px;
  margin: 0 auto;
  padding: 24px 16px 0;
}
.status-block {
  margin-top: 16px;
  padding: 16px;
  background: #fff;
  border: 1px solid #e4e7ed;
  border-radius: 8px;
}
.category-panel {
  margin-top: 16px;
}
.category-header {
  background: #fff;
  border: 1px solid #e4e7ed;
  border-radius: 8px;
  padding: 20px 20px 16px;
  margin-bottom: 16px;
}
.category-title {
  margin: 0 0 6px;
  font-size: 22px;
}
.category-desc {
  margin: 0 0 8px;
  color: #666;
  font-size: 14px;
}
.category-count {
  font-size: 13px;
  color: #909399;
}
.section-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(160px, 1fr));
  gap: 12px;
}
.section-cell {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 14px 16px;
  background: #fff;
  border: 1px solid #e4e7ed;
  border-radius: 8px;
  text-decoration: none;
  transition: all 0.15s;
}
.section-cell:hover {
  border-color: #409eff;
  box-shadow: 0 2px 8px rgba(64, 158, 255, 0.12);
}
.section-cell-label {
  color: #333;
  font-size: 14px;
  font-weight: 500;
}
.section-cell-count {
  color: #909399;
  font-size: 12px;
  background: #f0f2f5;
  border-radius: 999px;
  padding: 1px 8px;
  white-space: nowrap;
}
.feed-error {
  margin-top: 16px;
}
.post-list {
  margin-top: 16px;
}
.section-header {
  display: flex;
  align-items: baseline;
  gap: 10px;
  margin-bottom: 16px;
}
.section-title {
  margin: 0;
  font-size: 20px;
}
.section-count {
  font-size: 13px;
  color: #909399;
}
.post-card {
  background: #fff;
  border: 1px solid #e4e7ed;
  border-radius: 8px;
  padding: 16px;
  margin-bottom: 16px;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.04);
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
/* 帖子卡片右上角：置顶标签 + 管理员置顶入口 */
.pin-area {
  margin-left: auto;
  display: flex;
  align-items: center;
  gap: 6px;
  flex-shrink: 0;
}
.pinned-badge {
  font-size: 11px;
  color: #fff;
  background: #e6a23c;
  border-radius: 4px;
  padding: 2px 7px;
  line-height: 1.4;
}
.pin-btn {
  font-size: 12px;
  padding: 0;
}
.post-title {
  font-size: 18px;
  margin: 0 0 8px;
  cursor: pointer;
}
.post-title:hover {
  color: #409eff;
}
.excerpt {
  color: #666;
  font-size: 14px;
  line-height: 1.6;
  margin: 0 0 12px;
  display: -webkit-box;
  -webkit-line-clamp: 3;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
.post-bottom {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 12px;
  font-size: 13px;
}
.stat-chip {
  color: #888;
  background: #f5f7fa;
  border-radius: 999px;
  padding: 2px 10px;
  font-size: 12px;
}
.stat-chip.heart {
  color: #f56c9a;
  background: #fdf2f7;
}
.enter-link {
  margin-left: auto;
}
</style>
