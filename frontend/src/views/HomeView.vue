<template>
  <div class="page">
    <div class="home-layout">
      <aside class="left-nav">
        <div v-for="group in leftNavGroups" :key="group.label" class="left-nav-group">
          <button class="left-nav-btn">{{ group.label }}</button>
          <!-- hover 到该按钮时在右侧浮出子按钮 -->
          <div class="left-nav-sub">
            <button
              v-for="child in group.children"
              :key="child.label"
              class="left-nav-sub-btn"
              @click="child.all ? goAllTopics() : child.to && router.push(child.to)"
            >
              {{ child.label }}
            </button>
          </div>
        </div>
      </aside>

      <div class="home-center">
        <header class="topbar">
          <div class="topbar-actions">
        <el-input
          v-model="searchKeyword"
          class="search-input"
          placeholder="搜索帖子标题、作者或内容"
          clearable
        />
        <el-button type="primary" @click="goCompose">发布话题</el-button>
      </div>
    </header>

        <div class="home-grid">
      <aside class="side-nav">
        <div class="side-title">分区</div>
        <button class="board-btn" :class="{ active: !activeCategory }" @click="selectBoard('')">
          <span class="board-main">
            <span class="board-name">全部话题</span>
            <span class="board-count">{{ totalCount }}</span>
          </span>
          <span class="board-desc">最新帖子与社区动态</span>
        </button>
        <button
          v-for="c in categories"
          :key="c"
          class="board-btn"
          :class="{ active: activeCategory === c }"
          @click="selectBoard(c)"
        >
          <span class="board-main">
            <span class="board-name">{{ c }}</span>
            <span class="board-count">{{ boardCountMap[c] ?? 0 }}</span>
          </span>
          <span class="board-desc">{{ categoryDescriptions[c] }}</span>
        </button>
      </aside>

      <div class="main-col">
        <div class="stats">
      <div class="stat">
        <span class="stat-label">帖子总数</span>
        <span class="stat-value">{{ stats.total }}</span>
      </div>
      <div class="stat">
        <span class="stat-label">今日新增</span>
        <span class="stat-value">{{ stats.today }}</span>
      </div>
      <div class="stat">
        <span class="stat-label">活跃分区</span>
        <span class="stat-value">{{ stats.boards }}</span>
      </div>
    </div>

    <div class="feed">
      <div v-if="loading" class="feed-skeleton">
        <div v-for="i in 3" :key="i" class="post-card">
          <el-skeleton :rows="4" animated />
        </div>
      </div>
      <el-alert
        v-else-if="loadError"
        :title="loadError"
        type="error"
        :closable="false"
        class="feed-error"
      />
      <template v-else>
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
        <div v-if="!posts.length" class="empty">{{ emptyText }}</div>
        </template>
      </div>
      </div>
    </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, watch } from 'vue'
import { useRouter } from 'vue-router'
import api from '../api'
import AttachmentList from '../components/AttachmentList.vue'
import { categories, categoryDescriptions, fetchTagStructure, sectionLabel, formatTime, isToday, getErrorMessage } from '../utils/format'

const router = useRouter()

const posts = ref([])
const loading = ref(false)
const loadError = ref('')

// kungal 风格最左侧导航列（占位数据，后续替换为真实入口）
// 一级按钮 hover 在右侧浮出子按钮；带 to 的子按钮点击会跳转
const leftNavGroups = [
  {
    label: '发布',
    children: [
      { label: '发布帖子', to: '/compose' },
      { label: '左侧边栏按钮1-2' },
      { label: '左侧边栏按钮1-3' },
      { label: '左侧边栏按钮1-4' },
    ],
  },
  { label: '话题', children: [
    { label: '全部话题', to: '/', all: true },
    { label: 'Galgame', to: '/tag/galgame' },
    { label: '技术交流', to: '/tag/technique' },
    { label: '其它话题', to: '/tag/others' },
  ] },
  { label: '左侧边栏按钮3', children: [
    { label: '左侧边栏按钮3-1' },
    { label: '左侧边栏按钮3-2' },
    { label: '左侧边栏按钮3-3' },
    { label: '左侧边栏按钮3-4' },
  ] },
  { label: '左侧边栏按钮4', children: [
    { label: '左侧边栏按钮4-1' },
    { label: '左侧边栏按钮4-2' },
    { label: '左侧边栏按钮4-3' },
    { label: '左侧边栏按钮4-4' },
  ] },
  { label: '左侧边栏按钮5', children: [
    { label: '左侧边栏按钮5-1' },
    { label: '左侧边栏按钮5-2' },
    { label: '左侧边栏按钮5-3' },
    { label: '左侧边栏按钮5-4' },
  ] },
  { label: '左侧边栏按钮6', children: [
    { label: '左侧边栏按钮6-1' },
    { label: '左侧边栏按钮6-2' },
    { label: '左侧边栏按钮6-3' },
    { label: '左侧边栏按钮6-4' },
  ] },
]
// 标签结构（来自 /api/tags）：用于帖子卡片把 category/tags key 转成中文 label
const tagCategories = ref([])

// 分区栏过滤状态：activeCategory 为 '' = 全部话题，选中某个分区后只显示该分区帖子
const activeCategory = ref('')
const boardCounts = ref([])
const boardCountMap = computed(() => {
  const m = {}
  for (const b of boardCounts.value) m[b.name] = b.count
  return m
})
// 全部话题计数 = 各分区计数之和（来自 /api/boards）
const totalCount = computed(() =>
  Object.values(boardCountMap.value).reduce((s, n) => s + (Number(n) || 0), 0)
)
const emptyText = computed(() => {
  if (searchKeyword.value.trim()) return '没有找到匹配的帖子。'
  return '还没有帖子，来发第一帖吧。'
})

const searchKeyword = ref('')
let searchTimer = null

const stats = computed(() => {
  const total = posts.value.length
  const today = posts.value.filter((p) => isToday(p.created_at)).length
  const boards = new Set(posts.value.map((p) => p.category)).size
  return { total, today, boards }
})

// 搜索防抖：输入停止约 300ms 后带 q 重新拉列表
watch(searchKeyword, () => {
  clearTimeout(searchTimer)
  searchTimer = setTimeout(() => {
    load()
  }, 300)
})

async function load() {
  loading.value = true
  loadError.value = ''
  try {
    // 过滤条件统一走 params 对象，以后新增筛选维度（作者/时间/排序等）在此追加即可
    const params = {}
    const q = searchKeyword.value.trim()
    if (q) params.q = q
    if (activeCategory.value) params.category = activeCategory.value
    const { data } = await api.get('/posts', { params })
    posts.value = data
  } catch (e) {
    loadError.value = getErrorMessage(e, '加载帖子失败')
  } finally {
    loading.value = false
  }
}

// 点击分区按钮：activeCategory 为空 = 全部话题，重新拉列表
function selectBoard(name) {
  activeCategory.value = name
  load()
}

// 左侧边栏「全部话题」：清空分区筛选并回到首页展示全部帖子
function goAllTopics() {
  activeCategory.value = ''
  load()
  router.push('/')
}

// 各分区帖子数（/api/boards），失败不阻塞页面
async function loadBoardCounts() {
  try {
    const { data } = await api.get('/boards')
    boardCounts.value = data || []
  } catch (e) {
    // 分区计数失败不影响页面使用
  }
}

// 拉取标签结构，供帖子卡片显示中文标签（失败时 fetchTagStructure 内部兜底为空数组）
async function loadTagStructure() {
  tagCategories.value = await fetchTagStructure()
}

// 帖子卡片分区标签：直接显示板块名（category），tags 标签显示 #label
function postCategoryTag(p) {
  return p.category || ''
}
function tagLabel(k) {
  return sectionLabel(tagCategories.value, k) || k
}

function goPost(id) {
  router.push(`/post/${id}`)
}

// 顶部「发布话题」入口：跳转到独立发帖页
function goCompose() {
  router.push('/compose')
}

onMounted(() => {
  load()
  loadBoardCounts()
  loadTagStructure()
})
</script>

<style scoped>
.page {
  width: 100%;
}
/* 内容区居中容器（左侧 110px 留给贴边导航列） */
.home-center {
  max-width: 1140px;
  margin: 0 auto;
  padding: 24px 16px 0;
}
.topbar {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 16px;
  flex-wrap: wrap;
  margin-bottom: 16px;
}
.topbar-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex: 1;
  justify-content: flex-end;
  min-width: 0;
}
.search-input {
  width: 260px;
}
.stats {
  display: flex;
  gap: 12px;
  margin-bottom: 16px;
}
.stat {
  flex: 1;
  background: #fff;
  border: 1px solid #e4e7ed;
  border-radius: 8px;
  padding: 12px 16px;
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.stat-label {
  color: #888;
  font-size: 13px;
}
.stat-value {
  font-size: 22px;
  font-weight: 600;
}
.feed-skeleton .post-card {
  margin-bottom: 16px;
}
.feed-error {
  margin-bottom: 16px;
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
.empty {
  text-align: center;
  color: #909399;
  padding: 32px 0;
}

/* 最左侧导航列（仿 kungal 左侧 tab 列）+ 中间的 分区栏/主列 两栏 */
.home-layout {
  display: grid;
  grid-template-columns: 120px minmax(0, 1fr);
  gap: 0;
  align-items: start;
}
.left-nav {
  position: sticky;
  top: 24px;
  /* 浮出子面板要盖住右侧分区栏：给整列 z-index 提升层叠上下文（否则分区栏 sticky 会压在子面板上面） */
  z-index: 30;
  border-right: 1px solid #e4e7ed;
  padding: 6px 0;
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.left-nav-btn {
  text-align: center;
  padding: 10px 6px;
  font-size: 13px;
  color: #666;
  border: none;
  background: transparent;
  cursor: pointer;
  transition: color 0.15s, background 0.15s;
}
.left-nav-btn:hover {
  color: #409eff;
  font-weight: 600;
  background: #ecf5ff;
}
.left-nav-group {
  position: relative;
}
/* 子按钮面板：hover 一级按钮时在右侧浮出，贴住左列右边缘（不断开 hover） */
.left-nav-sub {
  position: absolute;
  top: 0;
  left: 100%;
  display: none;
  flex-direction: column;
  gap: 2px;
  min-width: 140px;
  padding: 6px;
  background: #fff;
  border: 1px solid #e4e7ed;
  border-radius: 6px;
  box-shadow: 0 4px 16px rgba(0, 0, 0, 0.1);
  z-index: 20;
}
.left-nav-group:hover .left-nav-sub {
  display: flex;
}
.left-nav-sub-btn {
  text-align: left;
  padding: 8px 10px;
  font-size: 12px;
  color: #666;
  border: none;
  background: transparent;
  cursor: pointer;
  white-space: nowrap;
  border-radius: 4px;
  transition: color 0.15s, background 0.15s;
}
.left-nav-sub-btn:hover {
  color: #409eff;
  font-weight: 600;
  background: #ecf5ff;
}

/* 首页两栏布局：左分区栏 + 右主列 */
.home-grid {
  display: grid;
  grid-template-columns: 200px minmax(0, 1fr);
  gap: 20px;
  align-items: start;
}
.main-col {
  min-width: 0;
}
.side-nav {
  position: sticky;
  top: 16px;
  background: #fff;
  border: 1px solid #e4e7ed;
  border-radius: 8px;
  padding: 10px;
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.side-title {
  font-size: 13px;
  font-weight: 600;
  color: #909399;
  padding: 4px 8px 10px;
  border-bottom: 1px solid #f0f2f5;
  margin-bottom: 6px;
}
.board-btn {
  display: flex;
  flex-direction: column;
  gap: 2px;
  text-align: left;
  padding: 8px 10px;
  border: none;
  background: transparent;
  border-radius: 6px;
  cursor: pointer;
  font: inherit;
  color: inherit;
  text-decoration: none;
  transition: background 0.15s;
}
.board-btn:hover {
  background: #f5f7fa;
}
.board-btn.board-current {
  cursor: default;
}
.board-btn.active {
  background: #ecf5ff;
}
.board-btn.active .board-name {
  color: #409eff;
  font-weight: 600;
}
.board-main {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}
.board-name {
  font-size: 14px;
  color: #333;
}
.board-count {
  font-size: 12px;
  color: #909399;
  background: #f0f2f5;
  border-radius: 999px;
  padding: 1px 8px;
  white-space: nowrap;
}
.board-btn.active .board-count {
  background: #d9ecff;
  color: #409eff;
}
.board-desc {
  font-size: 12px;
  color: #909399;
  line-height: 1.4;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
/* 中屏：隐藏最左侧导航列（占位阶段） */
@media (max-width: 900px) {
  .home-layout {
    grid-template-columns: 1fr;
  }
  .left-nav {
    display: none;
  }
}

/* 窄屏：分区栏收起为顶部横向滚动按钮 */
@media (max-width: 760px) {
  .home-grid {
    grid-template-columns: 1fr;
  }
  .side-nav {
    position: static;
    flex-direction: row;
    overflow-x: auto;
    padding: 8px;
    gap: 6px;
  }
  .side-title {
    display: none;
  }
  .board-btn {
    min-width: 148px;
  }
  .board-desc {
    display: none;
  }
}
</style>
