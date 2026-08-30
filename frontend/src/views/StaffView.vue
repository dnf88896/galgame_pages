<template>
  <div class="page">
    <div class="gal-center">
      <header class="gal-header">
        <div class="gal-header-row">
          <button class="gal-back-btn" @click="goHome">← 返回首页</button>
          <div class="gal-header-actions">
            <!-- 审核按钮红点：管理员有待审条目时在右上角显示数量徽标（无则隐藏） -->
            <el-badge
              v-if="isAdmin"
              :value="reviewCount?.staff ?? 0"
              :hidden="!isAdmin || !(reviewCount?.staff > 0)"
              :max="99"
            >
              <button class="gal-review-btn" @click="router.push('/staff/review')">审核制作人员</button>
            </el-badge>
            <button v-if="isLoggedIn" class="gal-mine-btn" @click="router.push('/staff/mine')">我的提交</button>
            <button v-if="isLoggedIn" class="gal-add-btn" @click="router.push('/staff/new')">+ 添加制作人员</button>
          </div>
        </div>
        <h1 class="gal-title">制作人员</h1>
        <p class="gal-subtitle">收录 Galgame 制作人员信息。按名称搜索，登录用户可提交，由管理员审核后上架。</p>
      </header>

      <!-- 搜索：制作人员名称关键词，与排序可叠加 -->
      <div class="gal-search">
        <el-input
          v-model="searchWord"
          class="gal-search-input"
          placeholder="输入制作人员名称搜索…"
          clearable
          @keyup.enter="load"
          @clear="load"
        >
          <template #append>
            <el-button @click="load">搜索</el-button>
          </template>
        </el-input>
      </div>

      <div class="gal-filters">
        <div class="gal-filter-row">
          <span class="gal-filter-name">排序</span>
          <div class="gal-filter-btns">
            <button
              v-for="opt in sortOptions"
              :key="opt.value"
              class="gal-filter-btn"
              :class="{ active: isSortActive(opt.value) }"
              @click="setSort(opt.value)"
            >{{ opt.label }}<span v-if="sortArrowOf(opt.value)" class="gal-sort-arrow">{{ sortArrowOf(opt.value) }}</span></button>
          </div>
        </div>
      </div>

      <!-- 加载中骨架屏 -->
      <div v-if="loading" class="gal-list">
        <div v-for="i in 3" :key="i" class="gal-skel-card">
          <el-skeleton :rows="4" animated />
        </div>
      </div>

      <!-- 加载失败 -->
      <el-alert
        v-else-if="loadError"
        :title="loadError"
        type="error"
        :closable="false"
        class="gal-error"
      />

      <!-- 列表 / 空状态 -->
      <template v-else>
        <div v-if="staffs.length" class="gal-list">
          <article v-for="s in staffs" :key="s.id" class="gal-card" @click="goDetail(s.id)">
            <div class="gal-cover">
              <img
                v-if="s.image && !s._coverError"
                :src="resolveAssetUrl(s.image)"
                :alt="s.name"
                loading="lazy"
                @error="coverImgError(s)"
              />
              <div v-else class="gal-cover-placeholder">制作人员</div>
            </div>
            <div class="gal-card-body">
              <div class="gal-card-head">
                <h2 class="gal-name">{{ s.name }}</h2>
              </div>
              <p v-if="s.description" class="gal-desc">{{ s.description }}</p>
              <div class="gal-views">
                <span>浏览 {{ s.view_count || 0 }}</span>
                <span v-if="s.created_at">创建：{{ formatTime(s.created_at) }}</span>
              </div>
            </div>
          </article>
        </div>
        <div v-else class="gal-empty">
          <p class="gal-empty-text">{{ emptyTitle }}</p>
          <p v-if="emptyDesc" class="gal-empty-desc">{{ emptyDesc }}</p>
        </div>
        <div v-if="hasMore && !loading" class="gal-load-more">
          <el-button :loading="loadingMore" @click="loadMore">加载更多</el-button>
        </div>
      </template>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import api from '../api'
import { getErrorMessage, resolveAssetUrl, formatTime } from '../utils/format'
import { user } from '../store/user'

const router = useRouter()

// 管理员（admin_level > 0）才显示「审核制作人员」入口；等级来自 store，认证后实时刷新
const isAdmin = computed(() => Number(user.value?.admin_level) > 0)

// 登录用户（有 id）才显示「添加制作人员」入口
const isLoggedIn = computed(() => !!user.value?.id)

// 待审核数量红点：管理员可见（GET /api/review/pending-count → { galgame, company, staff, character, total }）
const reviewCount = ref(null)

async function loadReviewCount() {
  try {
    const { data } = await api.get('/review/pending-count')
    reviewCount.value = data && typeof data === 'object' ? data : null
  } catch (e) {
    // 拉取失败静默：红点不显示，不影响列表
    reviewCount.value = null
  }
}

// 搜索：名称关键词；排序：created 默认（最新在上）/ views 总浏览数（两态切换）
const searchWord = ref('')

const sortOptions = [
  { value: 'views', label: '总浏览数' },
  { value: 'created', label: '创建顺序' },
]
const sortBy = ref('created')

// 两态排序：按钮 value → { desc, asc } 对应的 sort 参数值（desc 是第一下/默认方向）
const SORT_DIR = {
  views: { desc: 'views', asc: 'views_asc' },
}

// 两态按钮：当前 asc → 切回 desc；当前 desc → 切 asc；尚未激活 → 第一下进 desc。created 单选
function setSort(val) {
  const dir = SORT_DIR[val]
  if (dir) {
    if (sortBy.value === dir.asc) sortBy.value = dir.desc
    else sortBy.value = sortBy.value === dir.desc ? dir.asc : dir.desc
  } else {
    sortBy.value = val
  }
  load()
}

// 选中态：两态按钮的两种方向都视为激活
function isSortActive(val) {
  const dir = SORT_DIR[val]
  if (dir) return sortBy.value === dir.desc || sortBy.value === dir.asc
  return sortBy.value === val
}

// 两态按钮右侧的箭头：desc（倒序）↑、asc（升序）↓，其它无箭头
function sortArrowOf(val) {
  const dir = SORT_DIR[val]
  if (!dir) return ''
  if (sortBy.value === dir.desc) return '↑'
  if (sortBy.value === dir.asc) return '↓'
  return ''
}

const staffs = ref([])
const loading = ref(false)
const loadError = ref('')

// 分页：列表接口按 limit/offset 增量加载（全量 30000+ 条一次渲染会超时/卡死，必须分页）
const PAGE_SIZE = 60
const page = ref(1)
const hasMore = ref(true)
const loadingMore = ref(false)

async function load() {
  // 首次加载 / 搜索、排序变化：重置到第一页（offset=0），清空已有列表
  loading.value = true
  loadError.value = ''
  page.value = 1
  hasMore.value = true
  try {
    const params = {
      q: searchWord.value.trim() || undefined,
      sort: sortBy.value,
      limit: PAGE_SIZE,
      offset: 0,
    }
    const { data } = await api.get('/staffs', { params })
    staffs.value = Array.isArray(data) ? data : []
    hasMore.value = staffs.value.length >= PAGE_SIZE
  } catch (e) {
    loadError.value = getErrorMessage(e, '加载失败')
  } finally {
    loading.value = false
  }
}

// 加载更多：按已加载页数追加下一页（offset = page*PAGE_SIZE），后端按 offset 分页不重复
async function loadMore() {
  if (loadingMore.value || !hasMore.value || loading.value) return
  loadingMore.value = true
  try {
    const params = {
      q: searchWord.value.trim() || undefined,
      sort: sortBy.value,
      limit: PAGE_SIZE,
      offset: page.value * PAGE_SIZE,
    }
    const { data } = await api.get('/staffs', { params })
    const arr = Array.isArray(data) ? data : []
    staffs.value.push(...arr)
    hasMore.value = arr.length >= PAGE_SIZE
    page.value += 1
  } catch (e) {
    // 加载更多失败不打断已有列表，保持 hasMore 可重试
    hasMore.value = true
  } finally {
    loadingMore.value = false
  }
}

// 返回首页：退出制作人员页
function goHome() {
  router.push('/')
}

// 点击卡片进入详情页
function goDetail(id) {
  router.push(`/staff/${id}`)
}

// 封面加载失败时回退到占位块
function coverImgError(s) {
  s._coverError = true
}

// 空状态文案：搜索无结果 → 提示调整关键词；否则提示创建
const emptyTitle = computed(() => (searchWord.value.trim() ? '没有符合条件的制作人员。' : '暂无制作人员资料'))
const emptyDesc = computed(() =>
  searchWord.value.trim() ? '' : isLoggedIn.value ? '点击右上角「添加制作人员」创建' : ''
)

onMounted(() => {
  load()
  if (isAdmin.value) loadReviewCount()
})
</script>

<style scoped>
.page {
  width: 100%;
}
.gal-center {
  max-width: 1140px;
  margin: 0 auto;
  padding: 24px 16px 0;
}
.gal-header {
  margin-bottom: 16px;
}
.gal-header-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 10px;
}
.gal-back-btn {
  border: none;
  background: transparent;
  color: #409eff;
  font-size: 13px;
  cursor: pointer;
  padding: 0;
}
.gal-back-btn:hover {
  text-decoration: underline;
}
.gal-header-actions {
  display: flex;
  align-items: center;
  gap: 10px;
}
.gal-review-btn {
  border: 1px solid #e6a23c;
  background: #e6a23c;
  color: #fff;
  border-radius: 6px;
  padding: 4px 14px;
  font-size: 13px;
  line-height: 1.5;
  cursor: pointer;
  transition: all 0.15s;
}
.gal-review-btn:hover {
  background: #ebb563;
  border-color: #ebb563;
}
.gal-mine-btn {
  border: 1px solid #409eff;
  background: #ecf5ff;
  color: #409eff;
  border-radius: 6px;
  padding: 4px 14px;
  font-size: 13px;
  line-height: 1.5;
  cursor: pointer;
  transition: all 0.15s;
}
.gal-mine-btn:hover {
  background: #409eff;
  color: #fff;
}
.gal-add-btn {
  border: 1px solid #409eff;
  background: #409eff;
  color: #fff;
  border-radius: 6px;
  padding: 4px 14px;
  font-size: 13px;
  line-height: 1.5;
  cursor: pointer;
  transition: all 0.15s;
}
.gal-add-btn:hover {
  background: #66b1ff;
  border-color: #66b1ff;
}
.gal-title {
  font-size: 24px;
  margin: 0 0 6px;
}
.gal-subtitle {
  color: #888;
  font-size: 14px;
  margin: 0;
}
.gal-search {
  display: flex;
  gap: 8px;
  margin-bottom: 16px;
}
.gal-search-input {
  flex: 1;
}
.gal-filters {
  background: #fff;
  border: 1px solid #e4e7ed;
  border-radius: 8px;
  padding: 14px 16px;
  margin-bottom: 16px;
}
.gal-filter-row {
  display: flex;
  align-items: flex-start;
  gap: 12px;
  padding: 6px 0;
}
.gal-filter-name {
  width: 52px;
  flex-shrink: 0;
  font-size: 14px;
  font-weight: 600;
  color: #666;
  line-height: 28px;
}
.gal-filter-btns {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
.gal-filter-btn {
  border: 1px solid #e4e7ed;
  background: #fff;
  color: #666;
  border-radius: 999px;
  padding: 4px 12px;
  font-size: 13px;
  line-height: 1.5;
  cursor: pointer;
  transition: all 0.15s;
}
.gal-filter-btn:hover {
  background: #ecf5ff;
  color: #409eff;
  border-color: #b3d8ff;
}
.gal-filter-btn.active {
  background: #ecf5ff;
  color: #409eff;
  font-weight: 600;
  border-color: #409eff;
}
.gal-sort-arrow {
  margin-left: 4px;
  font-size: 11px;
  line-height: 1;
}
.gal-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
  margin-bottom: 16px;
}
.gal-skel-card {
  background: #fff;
  border: 1px solid #e4e7ed;
  border-radius: 8px;
  padding: 16px;
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
  height: 120px;
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
  justify-content: space-between;
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
  -webkit-line-clamp: 2;
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
.gal-load-more {
  display: flex;
  justify-content: center;
  padding: 24px 0 8px;
}
.gal-error {
  margin-bottom: 16px;
}
.gal-empty {
  text-align: center;
  padding: 64px 0;
}
.gal-empty-text {
  font-size: 18px;
  font-weight: 600;
  color: #909399;
  margin: 0 0 8px;
}
.gal-empty-desc {
  font-size: 14px;
  color: #b0b3b8;
  margin: 0;
}
</style>
