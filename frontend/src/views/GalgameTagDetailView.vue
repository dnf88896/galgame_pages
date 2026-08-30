<template>
  <div class="page">
    <div class="gal-detail-center">
      <!-- 加载中骨架屏 -->
      <div v-if="loading" class="gal-detail-skel">
        <el-skeleton :rows="8" animated />
      </div>

      <!-- 404：标签不存在（含 pending/rejected 无权限） -->
      <div v-else-if="notFound" class="gal-detail-empty">
        <p class="gal-detail-empty-text">标签不存在</p>
        <el-button type="primary" @click="goBack">返回标签库</el-button>
      </div>

      <!-- 加载失败 -->
      <el-alert
        v-else-if="loadError"
        :title="loadError"
        type="error"
        :closable="false"
        class="gal-detail-error"
      />

      <template v-else-if="detail">
        <div class="gal-detail-head">
          <el-button link type="primary" @click="goBack">← 返回</el-button>
        </div>
        <!-- 标签信息卡 -->
        <div class="tag-detail-card">
          <div class="tag-detail-head">
            <h1 class="tag-detail-name">{{ detail.name }}</h1>
            <span class="gal-tag-chip" :class="`cat-${detail.category}`">{{ CATEGORY_LABELS[detail.category] || detail.category }}</span>
            <span v-if="detail.spoiler_level > 0" class="gal-tag-spoiler" :class="`lvl-${detail.spoiler_level}`">{{ SPOILER_LABELS[detail.spoiler_level] || '剧透' }}</span>
          </div>
          <p v-if="detail.description" class="tag-detail-desc">{{ detail.description }}</p>
          <div class="tag-detail-meta">
            <span>共 {{ tagWorkCount }} 部作品</span>
            <span v-if="detail.creator">创建人：{{ detail.creator }}</span>
          </div>
        </div>

        <!-- 作品列表：该标签下已上架作品 -->
        <div class="works-section">
          <h2 class="works-title">作品</h2>
          <div v-if="works.length" class="works-list">
            <article v-for="w in works" :key="w.id" class="gal-card" @click="goWork(w.id)">
              <div class="gal-cover">
                <img
                  v-if="w.image && !w._coverError"
                  :src="resolveAssetUrl(w.image)"
                  :alt="w.name"
                  loading="lazy"
                  @error="workCoverError(w)"
                />
                <div v-else class="gal-cover-placeholder">作品</div>
              </div>
              <div class="gal-card-body">
                <div class="gal-card-head">
                  <h3 class="gal-name">{{ w.name }}</h3>
                </div>
                <p v-if="w.description" class="gal-desc">{{ w.description }}</p>
                <div class="gal-views">
                  <span>浏览 {{ w.view_count || 0 }}</span>
                  <span v-if="w.rating_avg != null">评分 {{ Number(w.rating_avg).toFixed(2) }} / 10</span>
                  <span v-else>暂无评分</span>
                </div>
              </div>
            </article>
          </div>
          <div v-if="hasMoreWorks" class="works-load-more">
            <el-button :loading="worksLoading" @click="loadMoreWorks">加载更多</el-button>
          </div>
          <div v-else class="works-empty">
            <p class="works-empty-text">暂无使用该标签的作品</p>
          </div>
        </div>
      </template>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import api from '../api'
import { getErrorMessage, resolveAssetUrl } from '../utils/format'
import { CATEGORY_LABELS, SPOILER_LABELS } from '../constants/galgameTag'

const route = useRoute()
const router = useRouter()

const tagId = route.params.id

// 作品分页：热门标签下作品多，一次全渲染会卡死 → 每页 60 + 加载更多
const PAGE_SIZE = 60

const detail = ref(null)
const loading = ref(false)
const notFound = ref(false)
const loadError = ref('')
const works = ref([])
const worksLoading = ref(false)

// 作品总数：详情接口 galgame_count（该标签下已上架作品数）
const worksTotal = computed(() => detail.value?.galgame_count ?? 0)
const hasMoreWorks = computed(() => works.value.length < worksTotal.value)

// 作品计数展示：总数优先 galgame_count，兜底已加载 works 长度
const tagWorkCount = computed(() => {
  if (detail.value?.galgame_count != null) return detail.value.galgame_count
  return works.value.length
})

async function loadDetail() {
  loading.value = true
  notFound.value = false
  loadError.value = ''
  works.value = []
  try {
    const { data } = await api.get(`/galgame-tags/${tagId}`, {
      params: { limit: PAGE_SIZE, offset: 0 },
    })
    detail.value = data
    works.value = Array.isArray(data?.works) ? data.works : []
  } catch (e) {
    if (e?.response?.status === 404) {
      notFound.value = true
    } else {
      loadError.value = getErrorMessage(e, '加载失败')
    }
  } finally {
    loading.value = false
  }
}

// 加载更多：按已加载条数做 offset 追加下一页（后端按 offset 分页不重复）
async function loadMoreWorks() {
  if (worksLoading.value || !hasMoreWorks.value) return
  worksLoading.value = true
  try {
    const { data } = await api.get(`/galgame-tags/${tagId}`, {
      params: { limit: PAGE_SIZE, offset: works.value.length },
    })
    const arr = Array.isArray(data?.works) ? data.works : []
    works.value.push(...arr)
  } catch (e) {
    // 失败不打断已加载列表，按钮可重试
  } finally {
    worksLoading.value = false
  }
}

// 返回按钮：后退栈空（如直接输 URL 进入）时回标签库页
function goBack() {
  if (window.history.length > 1) router.back()
  else router.push('/galgame/tag')
}

// 点击作品卡片进入 galgame 详情页
function goWork(id) {
  router.push(`/galgame/${id}`)
}

// 作品封面加载失败时回退到占位块
function workCoverError(w) {
  w._coverError = true
}

onMounted(() => {
  loadDetail()
})
</script>

<style scoped>
.page {
  width: 100%;
}
.gal-detail-center {
  max-width: 900px;
  margin: 0 auto;
  padding: 24px 16px 0;
}
.gal-detail-skel {
  background: #fff;
  border: 1px solid #e4e7ed;
  border-radius: 8px;
  padding: 24px;
  margin-bottom: 16px;
}
.gal-detail-empty {
  text-align: center;
  padding: 64px 0;
  background: #fff;
  border: 1px solid #e4e7ed;
  border-radius: 8px;
  margin-bottom: 16px;
}
.gal-detail-empty-text {
  font-size: 18px;
  font-weight: 600;
  color: #909399;
  margin: 0 0 16px;
}
.gal-detail-error {
  margin-bottom: 16px;
}
.tag-detail-card {
  background: #fff;
  border: 1px solid #e4e7ed;
  border-radius: 8px;
  padding: 24px;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.04);
  margin-bottom: 16px;
}
.gal-detail-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 16px;
}
.tag-detail-head {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 12px;
  margin-bottom: 8px;
}
.tag-detail-name {
  margin: 0;
  font-size: 24px;
  font-weight: 700;
  color: #303133;
}
.gal-tag-chip {
  display: inline-flex;
  align-items: center;
  border-radius: 999px;
  padding: 3px 12px;
  font-size: 12px;
  line-height: 1.6;
}
.gal-tag-chip.cat-type,
.gal-tag-chip.cat-content {
  color: #409eff;
  background: #ecf5ff;
}
.gal-tag-chip.cat-language {
  color: #909399;
  background: #f4f4f5;
}
.gal-tag-chip.cat-platform,
.gal-tag-chip.cat-meta,
.gal-tag-chip.cat-technical {
  color: #67c23a;
  background: #f0f9eb;
}
.gal-tag-chip.cat-sexual {
  color: #f56c9a;
  background: #fdf2f7;
}
.gal-tag-spoiler {
  font-size: 12px;
  color: #e6a23c;
}
.gal-tag-spoiler.lvl-2 {
  color: #f56c6c;
}
.tag-detail-desc {
  margin: 12px 0 0;
  color: #666;
  font-size: 14px;
  line-height: 1.7;
  white-space: pre-wrap;
  word-break: break-word;
}
.tag-detail-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 16px;
  margin-top: 12px;
  color: #909399;
  font-size: 13px;
}
/* 作品列表（复用 GalgameView 的 .gal-card 风格） */
.works-section {
  margin-top: 24px;
}
.works-title {
  font-size: 20px;
  font-weight: 600;
  color: #303133;
  margin: 0 0 16px;
}
.works-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.gal-card {
  display: flex;
  gap: 16px;
  background: #fff;
  border: 1px solid #e4e7ed;
  border-radius: 8px;
  padding: 16px;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.04);
  cursor: pointer;
  transition: border-color 0.15s, box-shadow 0.15s;
}
.gal-card:hover {
  border-color: #409eff;
  box-shadow: 0 2px 8px rgba(64, 158, 255, 0.2);
}
.gal-cover {
  flex-shrink: 0;
  width: 120px;
  height: 150px;
  border-radius: 6px;
  overflow: hidden;
  background: #f5f7fa;
}
.gal-cover img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
}
.gal-cover-placeholder {
  width: 100%;
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #909399;
  font-size: 13px;
  background: #f0f2f5;
}
.gal-card-body {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
}
.gal-card-head {
  display: flex;
  align-items: flex-start;
  gap: 12px;
  flex-wrap: wrap;
}
.gal-name {
  font-size: 17px;
  font-weight: 700;
  color: #303133;
  margin: 0;
}
.gal-desc {
  margin: 8px 0 0;
  color: #666;
  font-size: 14px;
  line-height: 1.6;
  display: -webkit-box;
  -webkit-line-clamp: 3;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
.gal-views {
  display: flex;
  gap: 16px;
  margin-top: 10px;
  font-size: 12px;
  color: #909399;
}
.works-load-more {
  display: flex;
  justify-content: center;
  padding: 24px 0 8px;
}
.works-empty {
  text-align: center;
  padding: 32px 0;
  background: transparent;
  border: none;
}
.works-empty-text {
  font-size: 16px;
  font-weight: 600;
  color: #909399;
  margin: 0;
}
</style>
