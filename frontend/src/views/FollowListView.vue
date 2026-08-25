<template>
  <div class="page follow-list-page">
    <el-page-header :content="title" @back="goBack" />

    <el-card v-if="loading && !list.length" style="margin-top: 16px">
      <el-skeleton :rows="6" animated />
    </el-card>

    <el-alert
      v-else-if="loadError"
      :title="loadError"
      type="error"
      :closable="false"
      style="margin-top: 16px"
    />

    <el-empty
      v-else-if="!list.length"
      style="margin-top: 32px"
      :description="emptyText"
    />

    <el-card v-else style="margin-top: 16px">
      <div v-for="u in list" :key="u.id" class="user-item">
        <router-link :to="`/user/${u.id}`" class="user-avatar">
          <el-avatar v-if="u.avatar_url" :src="resolveAssetUrl(u.avatar_url)" :size="40" />
          <el-avatar v-else :size="40" class="avatar-text">{{
            (u.username || '?').slice(0, 1).toUpperCase()
          }}</el-avatar>
        </router-link>
        <div class="user-info">
          <router-link :to="`/user/${u.id}`" class="user-name">{{ u.username }}</router-link>
          <div v-if="u.bio" class="user-bio">{{ u.bio }}</div>
        </div>
        <el-button
          v-if="loggedIn && !isSelfRow(u)"
          size="small"
          :type="u.is_following ? 'default' : 'primary'"
          :plain="!!u.is_following"
          :loading="followingLiking.has(u.id)"
          @click="toggleFollow(u)"
        >
          {{ u.is_following ? '已关注' : '关注' }}
        </el-button>
      </div>
    </el-card>
  </div>
</template>

<script setup>
import { ref, computed, reactive, onMounted, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import api from '../api'
import { token, user, requireLogin } from '../store/user'
import { resolveAssetUrl, getErrorMessage } from '../utils/format'

const props = defineProps({
  tab: {
    type: String,
    default: 'following',
    validator: (v) => v === 'following' || v === 'followers',
  },
})

const route = useRoute()
const router = useRouter()

const ownerId = computed(() => Number(route.params.id))
const title = computed(() => (props.tab === 'following' ? 'TA 的关注' : 'TA 的粉丝'))
const emptyText = computed(() =>
  props.tab === 'following' ? 'TA 还没有关注任何人。' : '还没有人关注 TA。',
)
const loggedIn = computed(() => !!token.value)

const list = ref([])
const loading = ref(false)
const loadError = ref('')
const followingLiking = reactive(new Set())

async function load() {
  const id = ownerId.value
  if (!Number.isInteger(id) || id <= 0) {
    loadError.value = '无效的用户 ID。'
    return
  }
  loading.value = true
  loadError.value = ''
  try {
    const { data } = await api.get(`/users/${id}/${props.tab}`)
    list.value = Array.isArray(data) ? data : []
  } catch (e) {
    if (e.response?.status === 404) {
      loadError.value = '用户不存在。'
    } else {
      loadError.value = getErrorMessage(e, '加载失败')
    }
  } finally {
    loading.value = false
  }
}

function goBack() {
  router.push(`/user/${ownerId.value}`)
}

// 列表中该行是否就是当前登录用户自己（避免出现「关注自己」按钮）
function isSelfRow(u) {
  return !!user.value && Number(user.value.id) === Number(u.id)
}

async function toggleFollow(u) {
  if (!requireLogin(router)) return
  if (followingLiking.has(u.id)) return
  followingLiking.add(u.id)
  try {
    const { data } = await api.post(`/users/${u.id}/follow`)
    u.is_following = data.following
    ElMessage.success(data.following ? '关注成功' : '已取消关注')
  } catch (e) {
    ElMessage.error(getErrorMessage(e, '操作失败'))
  } finally {
    followingLiking.delete(u.id)
  }
}

watch(
  () => [props.tab, route.params.id],
  () => {
    list.value = []
    loadError.value = ''
    load()
  },
)

onMounted(() => load())
</script>

<style scoped>
.page.follow-list-page {
  max-width: 720px;
}
.user-item {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px 0;
  border-bottom: 1px solid #f0f0f0;
}
.user-item:last-child {
  border-bottom: none;
}
.user-avatar {
  flex-shrink: 0;
  display: inline-flex;
  text-decoration: none;
}
.avatar-text {
  background: #409eff;
  color: #fff;
  font-size: 18px;
  font-weight: 600;
}
.user-info {
  flex: 1;
  min-width: 0;
}
.user-name {
  color: #303133;
  font-size: 14px;
  font-weight: 500;
  text-decoration: none;
}
.user-name:hover {
  color: #409eff;
}
.user-bio {
  margin-top: 2px;
  color: #909399;
  font-size: 13px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
</style>
