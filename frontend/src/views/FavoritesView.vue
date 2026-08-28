<template>
  <div class="page favorites-page">
    <el-page-header :content="title" @back="goBack" />

    <el-card v-if="loading && !favorites.length" style="margin-top: 16px">
      <el-skeleton :rows="6" animated />
    </el-card>

    <el-empty
      v-else-if="notFound"
      style="margin-top: 32px"
      description="用户不存在。"
    />

    <el-empty
      v-else-if="hiddenError"
      style="margin-top: 32px"
      description="该用户已隐藏收藏。"
    />

    <el-alert
      v-else-if="loadError"
      :title="loadError"
      type="error"
      :closable="false"
      style="margin-top: 16px"
    />

    <el-card v-else style="margin-top: 16px">
      <div v-if="!favorites.length" class="fav-empty">
        {{ isSelf ? '你还没有收藏任何帖子。' : 'TA 还没有收藏任何帖子。' }}
      </div>
      <article v-for="p in favorites" :key="p.id" class="fav-item">
        <div class="fav-item-top">
          <el-tag size="small" type="primary" effect="light">{{ p.category }}</el-tag>
          <el-tag v-for="k in (p.tags || [])" :key="k" size="small" type="primary" effect="light">#{{ tagLabel(k) }}</el-tag>
          <span class="author">{{ p.author }}</span>
          <span class="dot">·</span>
          <span class="time">{{ formatTime(p.created_at) }}</span>
          <span v-if="isPinned(p)" class="pinned-badge">置顶</span>
        </div>
        <div class="fav-item-body">
          <div class="fav-item-main">
            <h2 class="fav-title" @click="goPost(p.id)">{{ p.title }}</h2>
            <div class="fav-meta">
              <span class="stat-chip">回复 {{ p.reply_count }}</span>
              <span class="stat-chip">浏览 {{ p.view_count }}</span>
            </div>
          </div>
          <el-button
            v-if="isSelf"
            size="small"
            :loading="removing.has(p.id)"
            @click="removeFavorite(p)"
          >
            取消收藏
          </el-button>
        </div>
      </article>
    </el-card>
  </div>
</template>

<script setup>
import { ref, computed, reactive, onMounted, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import api from '../api'
import { user } from '../store/user'
import { fetchTagStructure, sectionLabel, formatTime, getErrorMessage, isPinned } from '../utils/format'

const route = useRoute()
const router = useRouter()

const userId = computed(() => Number(route.params.id))
const profile = ref(null)
const favorites = ref([])
const loading = ref(false)
const notFound = ref(false)
const hiddenError = ref('')
const loadError = ref('')

// 标签结构：把收藏列表里的 tags key 转成中文 label
const tagCategories = ref([])

async function loadTagStructure() {
  tagCategories.value = await fetchTagStructure()
}

// 标签显示为 #label（如 #攻略），查不到则回退到原始 key
function tagLabel(k) {
  return sectionLabel(tagCategories.value, k) || k
}

const isSelf = computed(() => !!user.value && Number(user.value.id) === Number(userId.value))
const title = computed(() => {
  if (isSelf.value) return '我的收藏'
  return `${profile.value?.nickname || profile.value?.username || 'TA'} 的收藏`
})

// 正在取消收藏的帖子 id 集合（用于按钮 loading）
const removing = reactive(new Set())

async function load() {
  const id = userId.value
  if (!Number.isInteger(id) || id <= 0) {
    loadError.value = '无效的用户 ID。'
    return
  }
  loading.value = true
  notFound.value = false
  hiddenError.value = ''
  loadError.value = ''
  try {
    // 先取资料拿用户名（决定页标题），同时判断用户是否存在
    const { data: prof } = await api.get(`/users/${id}`)
    profile.value = prof
  } catch (e) {
    if (e.response?.status === 404) {
      notFound.value = true
    } else {
      loadError.value = getErrorMessage(e, '加载资料失败')
    }
    loading.value = false
    return
  }
  try {
    const { data } = await api.get(`/users/${id}/favorites`)
    favorites.value = Array.isArray(data) ? data : []
  } catch (e) {
    if (e.response?.status === 403) {
      hiddenError.value = getErrorMessage(e, '该用户已隐藏收藏')
    } else if (e.response?.status === 404) {
      notFound.value = true
    } else {
      loadError.value = getErrorMessage(e, '加载失败')
    }
  } finally {
    loading.value = false
  }
}

function goPost(id) {
  router.push(`/post/${id}`)
}

function goBack() {
  // 有后退记录则回上一页，否则回首页
  if (window.history.state?.back) {
    router.back()
  } else {
    router.push('/')
  }
}

// 取消收藏：POST /posts/{id}/favorite 为 toggle，成功后从列表移除该条
async function removeFavorite(p) {
  if (removing.has(p.id)) return
  removing.add(p.id)
  try {
    await api.post(`/posts/${p.id}/favorite`)
    favorites.value = favorites.value.filter((x) => Number(x.id) !== Number(p.id))
    ElMessage.success('已取消收藏')
  } catch (e) {
    ElMessage.error(getErrorMessage(e, '操作失败'))
  } finally {
    removing.delete(p.id)
  }
}

watch(
  () => route.params.id,
  () => {
    favorites.value = []
    profile.value = null
    notFound.value = false
    hiddenError.value = ''
    loadError.value = ''
    load()
  },
)

onMounted(() => {
  load()
  loadTagStructure()
})
</script>

<style scoped>
.page.favorites-page {
  max-width: 760px;
}
.fav-empty {
  color: #909399;
  text-align: center;
  padding: 24px 0;
}
.fav-item {
  padding: 14px 0;
  border-bottom: 1px solid #f0f0f0;
}
.fav-item:last-child {
  border-bottom: none;
}
.fav-item-top {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  font-size: 13px;
  color: #666;
  margin-bottom: 8px;
}
.author {
  color: #333;
  font-weight: 500;
}
.dot {
  color: #ccc;
}
.time {
  color: #999;
}
/* 置顶帖子标签 */
.pinned-badge {
  font-size: 11px;
  color: #fff;
  background: #e6a23c;
  border-radius: 4px;
  padding: 2px 7px;
  line-height: 1.4;
}
.fav-item-body {
  display: flex;
  align-items: center;
  gap: 12px;
}
.fav-item-main {
  flex: 1;
  min-width: 0;
}
.fav-title {
  font-size: 16px;
  margin: 0 0 8px;
  cursor: pointer;
  color: #303133;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.fav-title:hover {
  color: #409eff;
}
.fav-meta {
  display: flex;
  align-items: center;
  gap: 8px;
}
.stat-chip {
  color: #888;
  background: #f5f7fa;
  border-radius: 999px;
  padding: 2px 10px;
  font-size: 12px;
}
</style>
