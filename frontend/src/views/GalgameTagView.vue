<template>
  <div class="page">
    <div class="tag-lib-center">
      <div class="tag-lib-head">
        <el-page-header content="标签库" @back="goBack" />
      </div>

      <!-- 搜索框：GET /api/galgame-tags?q=（列表 q，按名称模糊搜，仅 approved） -->
      <div class="tag-lib-search">
        <el-input
          v-model="keyword"
          placeholder="搜索标签名称…"
          clearable
          @keyup.enter="load"
          @clear="load"
        >
          <template #append>
            <el-button @click="load">搜索</el-button>
          </template>
        </el-input>
      </div>

      <!-- 加载失败 -->
      <el-alert v-if="loadError" :title="loadError" type="error" :closable="false" class="tag-lib-error" />

      <!-- 加载中骨架屏 -->
      <div v-else-if="loading" class="tag-lib-skel">
        <el-skeleton :rows="8" animated />
      </div>

      <!-- 分组标签网格 -->
      <template v-else>
        <div v-if="grouped.length" class="tag-lib-groups">
          <div v-for="g in grouped" :key="g.category" class="tag-group">
            <h3 class="tag-group-title">{{ g.label }}</h3>
            <div class="tag-group-grid">
              <span
                v-for="t in g.items"
                :key="t.id"
                class="gal-tag-chip"
                :class="`cat-${t.category}`"
                @click="goTag(t.id)"
              >
                {{ t.name }}<span v-if="t.galgame_count" class="gal-tag-count">+{{ t.galgame_count }}</span>
              </span>
            </div>
          </div>
        </div>
        <div v-else class="tag-lib-empty">
          <p class="tag-lib-empty-text">暂无标签</p>
        </div>
      </template>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, watch, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import api from '../api'
import { getErrorMessage } from '../utils/format'
import { goBack as navGoBack } from '../utils/navigation'
import { CATEGORY_LABELS, TAG_LIBRARY_CATEGORY_ORDER } from '../constants/galgameTag'

const router = useRouter()

const tags = ref([])
const loading = ref(false)
const loadError = ref('')
const keyword = ref('')

// 按 category 分组展示（顺序：type/language/platform/content/meta/technical/sexual）
const grouped = computed(() => {
  const map = {}
  for (const t of tags.value) {
    if (!map[t.category]) map[t.category] = []
    map[t.category].push(t)
  }
  return TAG_LIBRARY_CATEGORY_ORDER
    .filter((cat) => Array.isArray(map[cat]) && map[cat].length)
    .map((cat) => ({ category: cat, label: CATEGORY_LABELS[cat] || cat, items: map[cat] }))
})

async function load() {
  loading.value = true
  loadError.value = ''
  try {
    const { data } = await api.get('/galgame-tags', {
      params: { q: keyword.value.trim() || undefined, sort: 'count' },
    })
    tags.value = Array.isArray(data) ? data : []
  } catch (e) {
    loadError.value = getErrorMessage(e, '加载失败')
  } finally {
    loading.value = false
  }
}

// 输入防抖：停止约 300ms 后重搜
let searchTimer = null
watch(keyword, () => {
  clearTimeout(searchTimer)
  searchTimer = setTimeout(() => {
    load()
  }, 300)
})

// 返回按钮：有站内上一页就真后退，否则兜底回 galgame 列表页（见 utils/navigation.js 注释）
function goBack() {
  navGoBack(router, '/galgame')
}

// 点击标签 chip → 标签详情页
function goTag(id) {
  router.push(`/galgame/tag/${id}`)
}

onMounted(() => {
  load()
})
</script>

<style scoped>
.page {
  width: 100%;
}
.tag-lib-center {
  max-width: 1140px;
  margin: 0 auto;
  padding: 24px 16px 0;
}
.tag-lib-head {
  margin-bottom: 16px;
}
.tag-lib-search {
  max-width: 460px;
  margin-bottom: 16px;
}
.tag-lib-error {
  margin-bottom: 16px;
}
.tag-lib-skel {
  background: #fff;
  border: 1px solid #e4e7ed;
  border-radius: 8px;
  padding: 20px 24px;
}
.tag-lib-groups {
  display: flex;
  flex-direction: column;
  gap: 20px;
}
.tag-group {
  background: #fff;
  border: 1px solid #e4e7ed;
  border-radius: 8px;
  padding: 16px 20px;
}
.tag-group-title {
  margin: 0 0 12px;
  font-size: 15px;
  font-weight: 600;
  color: #303133;
}
.tag-group-grid {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
.gal-tag-chip {
  display: inline-flex;
  align-items: center;
  border-radius: 999px;
  padding: 3px 12px;
  font-size: 12px;
  line-height: 1.6;
  cursor: pointer;
  transition: opacity 0.15s;
}
.gal-tag-chip:hover {
  opacity: 0.8;
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
.gal-tag-count {
  margin-left: 4px;
  font-size: 11px;
  opacity: 0.75;
}
.tag-lib-empty {
  text-align: center;
  padding: 64px 0;
}
.tag-lib-empty-text {
  font-size: 18px;
  font-weight: 600;
  color: #909399;
  margin: 0;
}
</style>
