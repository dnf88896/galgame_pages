<template>
  <div class="page search-user-page">
    <el-page-header content="搜索用户" @back="$router.push('/')" />

    <div class="search-bar">
      <el-input
        v-model="keyword"
        placeholder="输入用户名搜索…"
        clearable
        size="large"
        @keyup.enter="doSearch"
      >
        <template #append>
          <el-button :loading="loading" @click="doSearch">搜索</el-button>
        </template>
      </el-input>
    </div>

    <div v-if="loading" style="margin-top: 16px">
      <el-skeleton :rows="5" animated />
    </div>

    <el-empty
      v-else-if="searched && !results.length"
      description="没有找到匹配的用户"
      style="margin-top: 32px"
    />

    <el-card v-else-if="results.length" style="margin-top: 16px">
      <div
        v-for="u in results"
        :key="u.id"
        class="user-item"
        @click="$router.push(`/user/${u.id}`)"
      >
        <el-avatar v-if="u.avatar_url" :src="resolveAssetUrl(u.avatar_url)" :size="40" />
        <el-avatar v-else :size="40" class="user-avatar-text">{{ firstChar(u) }}</el-avatar>
        <div class="user-meta">
          <div class="user-name">
            <span>{{ u.username }}</span>
            <el-tag v-if="Number(u.admin_level) > 0" size="small" type="warning">管理员 Lv.{{ u.admin_level }}</el-tag>
            <el-tag v-if="u.ban_until && new Date(u.ban_until).getTime() > Date.now()" size="small" type="danger">已封禁</el-tag>
          </div>
          <div class="user-bio">{{ u.bio || '这个人很懒，还没有写签名。' }}</div>
        </div>
      </div>
    </el-card>

    <el-empty v-else description="输入用户名后点击搜索" style="margin-top: 32px" />
  </div>
</template>

<script setup>
import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import api from '../api'
import { resolveAssetUrl, getErrorMessage } from '../utils/format'

const keyword = ref('')
const results = ref([])
const loading = ref(false)
const searched = ref(false)

function firstChar(u) {
  return (u.username || '?').slice(0, 1).toUpperCase()
}

async function doSearch() {
  const kw = keyword.value.trim()
  if (!kw) {
    ElMessage.warning('请输入要搜索的用户名')
    return
  }
  loading.value = true
  searched.value = true
  try {
    const { data } = await api.get('/users/search', { params: { q: kw } })
    results.value = Array.isArray(data) ? data : []
  } catch (e) {
    ElMessage.error(getErrorMessage(e, '搜索失败'))
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.page.search-user-page {
  max-width: 640px;
}
.search-bar {
  margin-top: 20px;
}
.user-item {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 10px 4px;
  cursor: pointer;
  border-radius: 8px;
  transition: background 0.2s;
}
.user-item:hover {
  background: #f5f7fa;
}
.user-avatar-text {
  background: #409eff;
  color: #fff;
  font-weight: 600;
}
.user-meta {
  min-width: 0;
}
.user-name {
  display: flex;
  align-items: center;
  gap: 8px;
  font-weight: 500;
  color: #303133;
}
.user-bio {
  font-size: 13px;
  color: #909399;
  margin-top: 2px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
</style>
