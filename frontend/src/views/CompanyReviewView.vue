<template>
  <div class="page">
    <div class="review-center">
      <div class="review-head">
        <el-page-header content="审核会社信息" @back="goBack" />
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
                <div class="gal-cover-placeholder">会社</div>
              </div>
              <div class="gal-card-body">
                <div class="gal-card-head">
                  <div class="gal-review-name-row">
                    <h2 class="gal-name">{{ p.name }}</h2>
                    <!-- 提交类型标注：update=修改（橙）、create=创建（蓝）；旧后端无 apply_type 时走 else 显示「创建」 -->
                    <el-tag v-if="p.apply_type === 'update'" type="warning" size="small">修改</el-tag>
                    <el-tag v-else type="primary" size="small">创建</el-tag>
                  </div>
                </div>
                <p v-if="p.apply_type === 'update'" class="gal-meta-line gal-origin-line">修改自：{{ p.original_name || ('#' + p.original_id) }}</p>
                <p v-if="p.description" class="gal-desc">{{ p.description }}</p>
                <p v-if="p.creator" class="gal-meta-line">提交人：{{ p.creator }}</p>
                <p v-if="p.created_at" class="gal-meta-line">提交时间：{{ formatTime(p.created_at) }}</p>
              </div>
            </article>
          </div>
          <div v-else class="review-empty">
            <p class="review-empty-text">暂无待审核的会社提交</p>
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
import { formatTime, getErrorMessage } from '../utils/format'
import { goBack as navGoBack } from '../utils/navigation'

const router = useRouter()

// 管理员（admin_level > 0）才能看待审核列表；等级来自 store，认证后实时刷新
const isAdmin = computed(() => Number(user.value?.admin_level) > 0)

const pendingList = ref([])
const loading = ref(false)
const loadError = ref('')

async function load() {
  loading.value = true
  loadError.value = ''
  try {
    const { data } = await api.get('/companies/pending')
    pendingList.value = Array.isArray(data) ? data : []
  } catch (e) {
    loadError.value = getErrorMessage(e, '加载失败')
  } finally {
    loading.value = false
  }
}

// 返回按钮：有站内上一页就真后退，否则兜底替换到下方目标页
// （判断依据与 replace 兜底的原因见 utils/navigation.js）
function goBack() {
  navGoBack(router, '/company')
}

// 点击卡片进入详情页审核
function goDetail(id) {
  router.push(`/company/${id}`)
}

onMounted(() => {
  if (isAdmin.value) {
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
  width: 120px;
  height: 120px;
  border-radius: 6px;
  overflow: hidden;
  background: #f5f7fa;
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
