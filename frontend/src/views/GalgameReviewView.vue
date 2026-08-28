<template>
  <div class="page">
    <div class="review-center">
      <div class="review-head">
        <el-page-header content="审核 Galgame 信息" @back="goBack" />
      </div>

      <!-- 无权限：非管理员不渲染列表 -->
      <div v-if="!isAdmin" class="review-denied">
        <p>需要管理员权限才能查看待审核列表。</p>
        <el-button type="primary" @click="goBack">返回</el-button>
      </div>

      <template v-else>
        <!-- 加载失败 -->
        <el-alert v-if="loadError" :title="loadError" type="error" :closable="false" class="review-error" />

        <!-- 加载中骨架屏 -->
        <div v-else-if="loading" class="review-list">
          <div v-for="i in 3" :key="i" class="gal-skel-card">
            <el-skeleton :rows="3" animated />
          </div>
        </div>

        <!-- 列表 / 空状态 -->
        <template v-else>
          <div v-if="pendingList.length" class="review-list">
            <article v-for="p in pendingList" :key="p.id" class="gal-card" @click="goDetail(p.id)">
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
                <p v-if="p.creator" class="gal-meta-line">提交人：{{ p.creator }}</p>
                <p v-if="p.created_at" class="gal-meta-line">提交时间：{{ formatTime(p.created_at) }}</p>
              </div>
            </article>
          </div>
          <div v-else class="review-empty">
            <p class="review-empty-text">暂无待审核的 Galgame 提交</p>
          </div>
        </template>
      </template>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import api from '../api'
import { user } from '../store/user'
import { fetchTagStructure, sectionLabel, formatTime, resolveAssetUrl, getErrorMessage } from '../utils/format'

const router = useRouter()

// 管理员（admin_level > 0）才能看待审核列表；等级来自 store，认证后实时刷新
const isAdmin = computed(() => Number(user.value?.admin_level) > 0)

const pendingList = ref([])
const loading = ref(false)
const loadError = ref('')
const tagCategories = ref([])

async function load() {
  loading.value = true
  loadError.value = ''
  try {
    const { data } = await api.get('/galgames/pending')
    pendingList.value = Array.isArray(data) ? data : []
  } catch (e) {
    loadError.value = getErrorMessage(e, '加载失败')
  } finally {
    loading.value = false
  }
}

// 返回按钮：后退栈空（如直接输 URL 进入）时回列表页
function goBack() {
  if (window.history.length > 1) router.back()
  else router.push('/galgame')
}

// 点击卡片进入详情页审核
function goDetail(id) {
  router.push(`/galgame/${id}`)
}

// 封面加载失败时回退到占位块
function coverImgError(p) {
  p._coverError = true
}

onMounted(async () => {
  if (isAdmin.value) {
    tagCategories.value = await fetchTagStructure()
    load()
  }
})
</script>

<style scoped>
.page {
  width: 100%;
}
.review-center {
  max-width: 1140px;
  margin: 0 auto;
  padding: 24px 16px 0;
}
.review-head {
  margin-bottom: 16px;
}
.review-denied {
  text-align: center;
  padding: 48px 0;
  background: #fff;
  border: 1px solid #e4e7ed;
  border-radius: 8px;
}
.review-denied p {
  margin: 0 0 16px;
  color: #606266;
}
.review-error {
  margin-bottom: 16px;
}
.review-list {
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
  border-color: #e6a23c;
  box-shadow: 0 2px 8px rgba(230, 162, 60, 0.2);
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
.gal-meta-line {
  margin: 8px 0 0;
  color: #888;
  font-size: 13px;
}
.review-empty {
  text-align: center;
  padding: 64px 0;
}
.review-empty-text {
  font-size: 18px;
  font-weight: 600;
  color: #909399;
  margin: 0;
}
</style>
