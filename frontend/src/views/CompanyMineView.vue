<template>
  <div class="page">
    <div class="mine-center">
      <div class="mine-head">
        <el-page-header content="我的提交" @back="goBack" />
      </div>

      <!-- 未登录：先登录再查看 -->
      <div v-if="!isLoggedIn" class="mine-denied">
        <p>请先登录后查看我的提交。</p>
        <el-button type="primary" @click="goLogin">去登录</el-button>
      </div>

      <template v-else>
        <!-- 加载失败 -->
        <el-alert v-if="loadError" :title="loadError" type="error" :closable="false" class="mine-error" />

        <!-- 加载中骨架屏 -->
        <div v-else-if="loading" class="mine-list">
          <div v-for="i in 3" :key="i" class="gal-skel-card">
            <el-skeleton :rows="3" animated />
          </div>
        </div>

        <!-- 列表 / 空状态 -->
        <template v-else>
          <div v-if="mineList.length" class="mine-list">
            <article v-for="p in mineList" :key="p.id" class="gal-card" @click="goDetail(p.id)">
              <div class="gal-cover">
                <div class="gal-cover-placeholder">会社</div>
              </div>
              <div class="gal-card-body">
                <div class="gal-card-head">
                  <div class="gal-review-name-row">
                    <h2 class="gal-name">{{ p.name }}</h2>
                    <!-- 提交类型标注：update=修改（橙）、create=创建（蓝）；旧数据无 apply_type 时走 else 显示「创建」 -->
                    <el-tag v-if="p.apply_type === 'update'" type="warning" size="small">修改</el-tag>
                    <el-tag v-else type="primary" size="small">创建</el-tag>
                  </div>
                  <el-tag v-if="p.status === 'pending'" type="warning" size="small">待审核</el-tag>
                  <el-tag v-else-if="p.status === 'rejected'" type="danger" size="small">已拒绝</el-tag>
                  <el-tag v-else-if="p.status === 'approved'" type="success" size="small">已上架</el-tag>
                </div>
                <p v-if="p.apply_type === 'update'" class="gal-meta-line gal-origin-line">修改自：{{ p.original_name || ('#' + p.original_id) }}</p>
                <p v-if="p.status === 'rejected' && p.reject_reason" class="gal-reject">拒绝理由：{{ p.reject_reason }}</p>
                <p v-if="p.created_at" class="gal-meta-line">提交时间：{{ formatTime(p.created_at) }}</p>
              </div>
            </article>
          </div>
          <div v-else class="mine-empty">
            <p class="mine-empty-text">你还没有提交过会社。</p>
            <el-button type="primary" plain @click="goNew">去提交</el-button>
          </div>
        </template>
      </template>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import api from '../api'
import { user } from '../store/user'
import { refreshMoe } from '../utils/moeGain'
import { formatTime, getErrorMessage } from '../utils/format'

const router = useRouter()
const route = useRoute()

const isLoggedIn = computed(() => !!user.value?.id)

const mineList = ref([])
const loading = ref(false)
const loadError = ref('')

async function load() {
  loading.value = true
  loadError.value = ''
  try {
    const { data } = await api.get('/companies/mine')
    mineList.value = Array.isArray(data) ? data : []
  } catch (e) {
    loadError.value = getErrorMessage(e, '加载失败')
  } finally {
    loading.value = false
  }
}

// 返回按钮：后退栈空（如直接输 URL 进入）时回列表页
function goBack() {
  if (window.history.length > 1) router.back()
  else router.push('/company')
}

function goLogin() {
  router.push({ path: '/login', query: { redirect: route.fullPath } })
}

// 点击卡片进入详情页编辑 / 删除
function goDetail(id) {
  router.push(`/company/${id}`)
}

function goNew() {
  router.push('/company/new')
}

onMounted(async () => {
  if (isLoggedIn.value) {
    // 萌点增量检测：提交者被动获得萌点（如提交被管理员审核通过 +10）时进入本页提示「+n萌点」
    refreshMoe()
    load()
  }
})
</script>

<style scoped>
.page {
  width: 100%;
}
.mine-center {
  max-width: 1140px;
  margin: 0 auto;
  padding: 24px 16px 0;
}
.mine-head {
  margin-bottom: 16px;
}
.mine-denied {
  text-align: center;
  padding: 48px 0;
  background: #fff;
  border: 1px solid #e4e7ed;
  border-radius: 8px;
}
.mine-denied p {
  margin: 0 0 16px;
  color: #606266;
}
.mine-error {
  margin-bottom: 16px;
}
.mine-list {
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
.gal-reject {
  margin: 8px 0 0;
  color: #f56c6c;
  font-size: 13px;
}
.gal-meta-line {
  margin: 8px 0 0;
  color: #888;
  font-size: 13px;
}
.mine-empty {
  text-align: center;
  padding: 64px 0;
}
.mine-empty-text {
  font-size: 18px;
  font-weight: 600;
  color: #909399;
  margin: 0 0 16px;
}
</style>
