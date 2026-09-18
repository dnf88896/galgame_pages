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
              :value="reviewCount?.company ?? 0"
              :hidden="!isAdmin || !(reviewCount?.company > 0)"
              :max="99"
            >
              <button class="gal-review-btn" @click="router.push('/company/review')">审核会社信息</button>
            </el-badge>
            <button v-if="isLoggedIn" class="gal-mine-btn" @click="router.push('/company/mine')">我的提交</button>
            <button v-if="isLoggedIn" class="gal-add-btn" @click="router.push('/company/new')">+ 添加会社</button>
          </div>
        </div>
        <h1 class="gal-title">会社资料</h1>
        <p class="gal-subtitle">收录 Galgame 制作会社信息。按名称搜索，登录用户可提交，由管理员审核后上架。</p>
      </header>

      <!-- 搜索：会社名称关键词，与排序可叠加 -->
      <div class="gal-search">
        <el-input
          v-model="searchWord"
          class="gal-search-input"
          placeholder="输入会社名称搜索…"
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
        <div v-if="companies.length" class="gal-list">
          <article v-for="c in companies" :key="c.id" class="gal-card" @click="goDetail(c.id)">
            <div class="gal-cover">
              <img
                v-if="c.logo_image && !c._coverError"
                :src="resolveAssetUrl(c.logo_image)"
                :alt="c.name"
                loading="lazy"
                @error="coverImgError(c)"
              />
              <div v-else class="gal-cover-placeholder">会社</div>
            </div>
            <div class="gal-card-body">
              <div class="gal-card-head">
                <h2 class="gal-name">{{ c.name }}</h2>
              </div>
              <p v-if="c.description" class="gal-desc">{{ c.description }}</p>
              <div class="gal-links">
                <button v-if="c.website" class="gal-link" @click.stop="openLink(c.website)">官网</button>
                <span v-else class="gal-no-link">暂无官网</span>
              </div>
              <div class="gal-views">
                <span>浏览 {{ c.view_count || 0 }}</span>
              </div>
            </div>
          </article>
        </div>
        <div v-else class="gal-empty">
          <p class="gal-empty-text">{{ emptyTitle }}</p>
          <p v-if="emptyDesc" class="gal-empty-desc">{{ emptyDesc }}</p>
        </div>
      </template>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, watch, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import api from '../api'
import { getErrorMessage, resolveAssetUrl } from '../utils/format'
import { user } from '../store/user'

const router = useRouter()

// 管理员（admin_level > 0）才显示「审核会社信息」入口；等级来自 store，认证后实时刷新
const isAdmin = computed(() => Number(user.value?.admin_level) > 0)

// 登录用户（有 id）才显示「添加会社」入口
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

const companies = ref([])
const loading = ref(false)
const loadError = ref('')

async function load() {
  loading.value = true
  loadError.value = ''
  try {
    const params = {
      q: searchWord.value.trim() || undefined,
      sort: sortBy.value,
    }
    const { data } = await api.get('/companies', { params })
    companies.value = Array.isArray(data) ? data : []
  } catch (e) {
    loadError.value = getErrorMessage(e, '加载失败')
  } finally {
    loading.value = false
  }
}

// 返回首页：退出会社页
function goHome() {
  router.push('/')
}

function openLink(url) {
  if (!url) return
  window.open(url, '_blank', 'noopener')
}

// 点击卡片进入详情页
function goDetail(id) {
  router.push(`/company/${id}`)
}

// 封面加载失败时回退到占位块
function coverImgError(c) {
  c._coverError = true
}

// 空状态文案：搜索无结果 → 提示调整关键词；否则提示创建
const emptyTitle = computed(() => (searchWord.value.trim() ? '没有符合条件的会社。' : '暂无会社资料'))
const emptyDesc = computed(() =>
  searchWord.value.trim() ? '' : isLoggedIn.value ? '点击右上角「添加会社」创建' : ''
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
  flex-wrap: wrap;
  justify-content: flex-end;
}
/* 手机端：三个入口按钮在标题行放不下，改为独占一行并允许换行，避免被裁掉 */
@media (max-width: 640px) {
  .gal-header-row {
    flex-wrap: wrap;
    gap: 8px;
  }
  .gal-header-actions {
    width: 100%;
    justify-content: flex-start;
  }
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
  width: 140px;
  height: 140px;
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
  /* 长英文名（如 Onomatope*Raspberry）无空格不可断，会撑破卡片并让整页横向滚动 */
  word-break: break-word;
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
.gal-links {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 12px;
}
.gal-link {
  border: 1px solid #b3d8ff;
  background: #ecf5ff;
  color: #409eff;
  border-radius: 6px;
  padding: 3px 10px;
  font-size: 12px;
  line-height: 1.5;
  cursor: pointer;
  transition: all 0.15s;
}
.gal-link:hover {
  background: #409eff;
  color: #fff;
}
.gal-no-link {
  color: #b0b3b8;
  font-size: 12px;
}
.gal-views {
  display: flex;
  gap: 16px;
  margin-top: 10px;
  font-size: 12px;
  color: #909399;
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
