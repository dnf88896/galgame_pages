<template>
  <div class="page">
    <div class="gal-center">
      <header class="gal-header">
        <div class="gal-header-row">
          <button class="gal-back-btn" @click="goHome">← 返回首页</button>
          <button v-if="isAdmin" class="gal-add-btn" @click="router.push('/galgame/new')">+ 添加galgame</button>
        </div>
        <h1 class="gal-title">Galgame 资源</h1>
        <p class="gal-subtitle">Galgame 资源页面，提供各类 Galgame 下载。按类型 / 语言 / 平台 / 作品分类筛选。</p>
      </header>

      <div class="gal-filters">
        <div v-for="row in rows" :key="row.key" class="gal-filter-row">
          <span class="gal-filter-name">{{ row.name }}</span>
          <div class="gal-filter-btns">
            <button
              class="gal-filter-btn"
              :class="{ active: filters[row.key] === '' }"
              @click="setFilter(row.key, '')"
            >{{ row.allLabel }}</button>
            <button
              v-for="opt in row.options"
              :key="opt.key"
              class="gal-filter-btn"
              :class="{ active: filters[row.key] === opt.key }"
              @click="setFilter(row.key, opt.key)"
            >{{ opt.label }}</button>
          </div>
        </div>
        <button v-if="hasFilter" class="gal-reset-btn" @click="resetFilters">重置筛选</button>
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
        <div v-if="galgames.length" class="gal-list">
          <article v-for="p in galgames" :key="p.id" class="gal-card" @click="goDetail(p.id)">
            <div class="gal-cover">
              <img
                v-if="p.image && !p._coverError"
                :src="resolveAssetUrl(p.image)"
                :alt="p.name"
                loading="lazy"
                @error="coverImgError(p)"
              />
              <div v-else class="gal-cover-placeholder">无封面</div>
            </div>
            <div class="gal-card-body">
              <div class="gal-card-head">
                <h2 class="gal-name">{{ p.name }}</h2>
                <div class="gal-tags">
                  <span v-for="k in (p.tags || [])" :key="k" class="gal-tag">#{{ sectionLabel(tagCategories, k) || k }}</span>
                </div>
              </div>
              <p v-if="p.staff" class="gal-staff">{{ p.staff }}</p>
              <p v-if="p.description" class="gal-desc">{{ p.description }}</p>
              <div class="gal-links">
                <template v-if="p.links && p.links.length">
                  <button
                    v-for="(link, i) in p.links"
                    :key="i"
                    class="gal-link"
                    @click.stop="openLink(link.url)"
                  >{{ link.label || link.url }}</button>
                </template>
                <span v-else class="gal-no-link">暂无资源链接</span>
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
import { ref, reactive, computed, watch, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import api from '../api'
import { fetchTagStructure, sectionLabel, getErrorMessage, resolveAssetUrl } from '../utils/format'
import { user } from '../store/user'

const router = useRouter()

// 管理员（admin_level > 0）才显示「添加galgame」入口；等级来自 store，认证后实时刷新
const isAdmin = computed(() => Number(user.value?.admin_level) > 0)

// 四行筛选配置（仿 kungal，行序一致：类型 / 语言 / 平台 / 作品；单选）：
// key 对应 filters 里的字段；prefix 是 galgame-resource 大类下小分支 key 的前缀
const ROWS = [
  { key: 'type', name: '类型', allLabel: '全部类型', prefix: 'gg-type-' },
  { key: 'lang', name: '语言', allLabel: '全部语言', prefix: 'gg-lang-' },
  { key: 'plat', name: '平台', allLabel: '全部平台', prefix: 'gg-plat-' },
  { key: 'work', name: '作品', allLabel: '全部作品', prefix: 'gg-work-' },
]

// 每行选项：从 galgame-resource 大类下按 prefix 分组，第一个是 allLabel（value='' 表示不筛选）
const rows = computed(() => {
  const gal = (tagCategories.value || []).find((c) => c && c.key === 'galgame-resource')
  const sections = Array.isArray(gal?.sections) ? gal.sections : []
  return ROWS.map((row) => ({
    ...row,
    options: sections
      .filter((s) => s && s.key && s.key.startsWith(row.prefix))
      .map((s) => ({ key: s.key, label: s.label })),
  }))
})

const filters = reactive({ type: '', lang: '', plat: '', work: '' })
const tagCategories = ref([])

const hasFilter = computed(() => Object.values(filters).some((v) => v !== ''))

// 所有非空筛选值 → 后端按 tags=a&tags=b 多标签 AND 过滤
const selectedSections = computed(() => Object.values(filters).filter((v) => v !== ''))

const galgames = ref([])
const loading = ref(false)
const loadError = ref('')

// axios 默认把数组序列化成 tags[]=a，后端契约要求重复 key（tags=a&tags=b），这里自定义序列化
function galParamsSerializer(params) {
  const parts = []
  Object.keys(params || {}).forEach((k) => {
    const v = params[k]
    if (Array.isArray(v)) {
      v.forEach((item) => {
        if (item !== '' && item != null) parts.push(`${k}=${encodeURIComponent(item)}`)
      })
    } else if (v !== '' && v != null) {
      parts.push(`${k}=${encodeURIComponent(v)}`)
    }
  })
  return parts.join('&')
}

async function load() {
  loading.value = true
  loadError.value = ''
  try {
    const { data } = await api.get('/galgames', {
      params: { tags: selectedSections.value },
      paramsSerializer: galParamsSerializer,
    })
    galgames.value = Array.isArray(data) ? data : []
  } catch (e) {
    loadError.value = getErrorMessage(e, '加载失败')
  } finally {
    loading.value = false
  }
}

function setFilter(key, value) {
  filters[key] = value
}

// 返回首页：退出 galgame 筛选页
function goHome() {
  router.push('/')
}

function resetFilters() {
  filters.type = ''
  filters.lang = ''
  filters.plat = ''
  filters.work = ''
}

async function loadTagStructure() {
  tagCategories.value = await fetchTagStructure()
}

function openLink(url) {
  if (!url) return
  window.open(url, '_blank', 'noopener')
}

// 点击卡片进入详情页
function goDetail(id) {
  router.push(`/galgame/${id}`)
}

// 封面加载失败时回退到占位块
function coverImgError(p) {
  p._coverError = true
}

// 空状态文案：无筛选 → 暂无内容（管理员提示创建）；有筛选但无结果 → 提示调整筛选
const emptyTitle = computed(() =>
  hasFilter.value ? '没有符合筛选条件的 Galgame。' : '暂无 Galgame 内容'
)
const emptyDesc = computed(() =>
  hasFilter.value ? '' : isAdmin.value ? '点击右上角「添加galgame」创建' : ''
)

// 筛选变化、首次进入都重新拉列表
watch(selectedSections, load)

onMounted(() => {
  loadTagStructure()
  load()
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
.gal-reset-btn {
  margin: 10px 0 2px;
  border: none;
  background: transparent;
  color: #409eff;
  font-size: 13px;
  cursor: pointer;
  padding: 0;
}
.gal-reset-btn:hover {
  text-decoration: underline;
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
  height: 170px;
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
.gal-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}
.gal-tag {
  color: #409eff;
  background: #ecf5ff;
  border-radius: 999px;
  padding: 2px 10px;
  font-size: 12px;
  line-height: 1.6;
}
.gal-staff {
  margin: 8px 0 0;
  color: #888;
  font-size: 13px;
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
