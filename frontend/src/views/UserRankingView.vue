<template>
  <div class="page user-ranking-page">
    <el-page-header content="用户排行" @back="() => goBack(router)" />

    <div class="rank-hint">✦ 按萌点从高到低排行，点击用户可进入其个人主页</div>

    <div v-if="loading" style="margin-top: 16px">
      <el-skeleton :rows="8" animated />
    </div>

    <el-empty v-else-if="!items.length" description="还没有用户" style="margin-top: 32px" />

    <el-card v-else style="margin-top: 16px">
      <div
        v-for="(u, i) in items"
        :key="u.id"
        class="user-item rank-item"
        @click="$router.push(`/user/${u.id}`)"
      >
        <span class="rank-badge" :class="rankClass(i)">{{ rankOf(i) }}</span>
        <el-avatar v-if="u.avatar_url" :src="resolveAssetUrl(u.avatar_url)" :size="40" />
        <el-avatar v-else :size="40" class="user-avatar-text">{{ firstChar(u) }}</el-avatar>
        <div class="user-meta">
          <div class="user-name">
            <span>{{ u.nickname || u.username }}</span>
            <el-tag v-if="Number(u.admin_level) > 0" size="small" type="warning">管理员 Lv.{{ u.admin_level }}</el-tag>
            <el-tag v-if="u.ban_until && new Date(u.ban_until).getTime() > Date.now()" size="small" type="danger">已封禁</el-tag>
          </div>
          <div class="user-bio">{{ u.bio || '这个人很懒，还没有写签名。' }}</div>
        </div>
        <span class="rank-moe">✦ {{ u.moe_points }} 萌点</span>
      </div>
    </el-card>

    <div v-if="total > pageSize" class="rank-pagination">
      <el-pagination
        background
        layout="prev, pager, next, total"
        :total="total"
        :page-size="pageSize"
        :current-page="page"
        @current-change="load"
      />
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import api from '../api'
import { goBack } from '../utils/navigation'
import { resolveAssetUrl, getErrorMessage } from '../utils/format'

const router = useRouter()

const items = ref([])
const total = ref(0)
const page = ref(1)
const pageSize = 20
const loading = ref(false)

function firstChar(u) {
  return (u.nickname || u.username || '?').slice(0, 1).toUpperCase()
}

// 全局排名 = 上一页总数 + 当前页序号
function rankOf(i) {
  return (page.value - 1) * pageSize + i + 1
}

function rankClass(i) {
  const r = rankOf(i)
  return r === 1 ? 'top1' : r === 2 ? 'top2' : r === 3 ? 'top3' : ''
}

async function load(p = 1) {
  page.value = p
  loading.value = true
  try {
    const { data } = await api.get('/users/ranking', { params: { page: page.value, page_size: pageSize } })
    items.value = (data && data.items) || []
    total.value = (data && data.total) || 0
  } catch (e) {
    ElMessage.error(getErrorMessage(e, '加载失败'))
  } finally {
    loading.value = false
  }
}

onMounted(() => load(1))
</script>

<style scoped>
.page.user-ranking-page {
  max-width: 640px;
}
.rank-hint {
  margin-top: 16px;
  font-size: 13px;
  color: #909399;
}
.rank-item {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 10px 4px;
  cursor: pointer;
  border-radius: 8px;
  transition: background 0.2s;
}
.rank-item:hover {
  background: #f5f7fa;
}
.rank-badge {
  width: 28px;
  height: 28px;
  flex-shrink: 0;
  border-radius: 50%;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  font-weight: 700;
  font-size: 14px;
  color: #fff;
  background: #c0c4cc;
}
.rank-badge.top1 {
  background: #f7ba2a;
}
.rank-badge.top2 {
  background: #a8adb7;
}
.rank-badge.top3 {
  background: #d09a6b;
}
/* 用户信息区：模板里的 .user-* 类此前没有对应样式（scoped 样式不跨组件，
   只在 SearchUserView 里定义过），导致昵称被压成竖条、「✦ N 萌点」被顶出屏幕 */
.user-meta {
  flex: 1;
  min-width: 0;
}
.user-name {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 4px 8px;
  font-weight: 500;
  color: #303133;
  word-break: break-word;
}
.user-bio {
  font-size: 13px;
  color: #909399;
  margin-top: 2px;
  word-break: break-word;
}
.user-avatar-text {
  background: #409eff;
  color: #fff;
  font-weight: 600;
}
.rank-moe {
  margin-left: auto;
  flex-shrink: 0;
  color: #e6a23c;
  font-weight: 600;
  font-size: 14px;
}
.rank-pagination {
  margin-top: 16px;
  display: flex;
  justify-content: center;
}
</style>
