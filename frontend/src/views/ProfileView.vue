<template>
  <div class="page profile-page">
    <el-page-header content="个人主页" @back="$router.push('/')" />

    <el-card v-if="loading && !profile" style="margin-top: 16px">
      <el-skeleton :rows="6" animated />
    </el-card>

    <el-alert
      v-else-if="loadError"
      :title="loadError"
      type="error"
      :closable="false"
      style="margin-top: 16px"
    />

    <el-empty v-else-if="notFound" style="margin-top: 32px" description="用户不存在。" />

    <template v-else-if="profile">
      <el-card class="profile-card" style="margin-top: 16px">
        <div class="profile-head">
          <el-avatar v-if="profile.avatar_url" :src="avatarSrc" :size="64" />
          <el-avatar v-else :size="64" class="avatar-text">{{ firstChar }}</el-avatar>
          <div class="profile-info">
            <div class="profile-name">{{ profile.username }}</div>
            <div class="profile-meta">注册于 {{ formatTime(profile.created_at) }}</div>
            <div v-if="profile.bio" class="profile-bio">{{ profile.bio }}</div>
            <div v-else class="profile-bio muted">这个人很懒，还没有写签名。</div>
          </div>
        </div>

        <!-- 非本人资料页：关注 / 私聊 / 屏蔽 操作按钮 -->
        <div v-if="!isSelf" class="profile-actions">
          <el-button
            size="small"
            :type="profile.is_following ? 'default' : 'primary'"
            :plain="!!profile.is_following"
            :loading="followingLoading"
            @click="toggleFollow"
          >
            {{ profile.is_following ? '已关注' : '关注' }}
          </el-button>
          <el-button size="small" @click="goMessage">私聊</el-button>
          <el-button
            size="small"
            :type="profile.is_blocked ? 'danger' : 'default'"
            :plain="!!profile.is_blocked"
            :loading="blockLoading"
            @click="blockUser"
          >
            {{ profile.is_blocked ? '已屏蔽' : '屏蔽' }}
          </el-button>
        </div>

        <div class="profile-stats">
          <div class="stat">
            <span class="stat-value">{{ profile.post_count }}</span>
            <span class="stat-label">发帖</span>
          </div>
          <div class="stat">
            <span class="stat-value">{{ profile.reply_count }}</span>
            <span class="stat-label">回帖</span>
          </div>
          <div class="stat">
            <span class="stat-value">{{ profile.received_likes }}</span>
            <span class="stat-label">获赞</span>
          </div>
          <router-link :to="`/user/${userId}/following`" class="stat stat-link">
            <span class="stat-value">{{ profile.following_count ?? 0 }}</span>
            <span class="stat-label">关注</span>
          </router-link>
          <router-link :to="`/user/${userId}/followers`" class="stat stat-link">
            <span class="stat-value">{{ profile.follower_count ?? 0 }}</span>
            <span class="stat-label">粉丝</span>
          </router-link>
        </div>

        <!-- 自己的资料页：编辑签名 / 更换头像 -->
        <template v-if="isSelf">
          <el-divider />
          <div class="edit-section">
            <div class="edit-title">编辑资料</div>
            <el-input
              v-model="bioDraft"
              type="textarea"
              :rows="3"
              maxlength="200"
              show-word-limit
              placeholder="写点签名介绍自己（可选）"
            />
            <div class="edit-actions">
              <el-button type="primary" :loading="bioSaving" @click="saveBio">保存签名</el-button>
              <span class="form-status" :class="{ error: editStatusError }">{{ editStatus }}</span>
            </div>
            <div class="avatar-upload">
              <span class="avatar-upload-label">更换头像：</span>
              <input type="file" accept="image/*" @change="onAvatarChange" />
              <span v-if="avatarUploading" class="form-status">上传中...</span>
            </div>
          </div>
        </template>
      </el-card>

      <!-- 预留：后续可在此扩展更多用户板块（如 TA 的回复、收藏等） -->
      <el-card class="posts-card" style="margin-top: 16px">
        <template #header>最近发布</template>
        <div v-if="!profile.recent_posts || !profile.recent_posts.length" class="recent-empty">
          还没有发布过帖子。
        </div>
        <div v-else class="recent-list">
          <div v-for="p in profile.recent_posts" :key="p.id" class="recent-item">
            <span class="recent-title" @click="goPost(p.id)">{{ p.title }}</span>
            <span class="recent-time">{{ formatTime(p.created_at) }}</span>
          </div>
        </div>
      </el-card>
    </template>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import api from '../api'
import { user, setUser, requireLogin } from '../store/user'
import { formatTime, resolveAssetUrl, getErrorMessage } from '../utils/format'

const route = useRoute()
const router = useRouter()

const userId = computed(() => Number(route.params.id))
const profile = ref(null)
const loading = ref(false)
const notFound = ref(false)
const loadError = ref('')

const bioDraft = ref('')
const bioSaving = ref(false)
const editStatus = ref('')
const editStatusError = ref(false)
const avatarUploading = ref(false)
const followingLoading = ref(false)
const blockLoading = ref(false)

const isSelf = computed(() => !!user.value && Number(user.value.id) === Number(userId.value))
const avatarSrc = computed(() => resolveAssetUrl(profile.value?.avatar_url))
const firstChar = computed(() => (profile.value?.username || '?').slice(0, 1).toUpperCase())

async function load() {
  const id = userId.value
  if (!Number.isInteger(id) || id <= 0) {
    notFound.value = true
    profile.value = null
    return
  }
  loading.value = true
  notFound.value = false
  loadError.value = ''
  try {
    const { data } = await api.get(`/users/${id}`)
    profile.value = data
    if (isSelf.value) bioDraft.value = data.bio || ''
  } catch (e) {
    if (e.response?.status === 404) {
      notFound.value = true
      profile.value = null
    } else {
      loadError.value = getErrorMessage(e, '加载资料失败')
    }
  } finally {
    loading.value = false
  }
}

async function saveBio() {
  if (!isSelf.value) return
  bioSaving.value = true
  editStatus.value = ''
  editStatusError.value = false
  try {
    const { data } = await api.put('/auth/profile', { bio: bioDraft.value.trim() })
    ElMessage.success('签名已保存')
    profile.value.bio = data.bio
    if (user.value) setUser({ ...user.value, bio: data.bio })
  } catch (e) {
    editStatus.value = getErrorMessage(e, '保存失败')
    editStatusError.value = true
  } finally {
    bioSaving.value = false
  }
}

async function onAvatarChange(e) {
  const file = e.target.files && e.target.files[0]
  e.target.value = ''
  if (!file) return
  const fd = new FormData()
  fd.append('file', file)
  avatarUploading.value = true
  try {
    const { data } = await api.post('/auth/avatar', fd)
    ElMessage.success('头像已更新')
    if (user.value) setUser({ ...user.value, avatar_url: data.avatar_url })
    await load() // 刷新公开资料
  } catch (err) {
    ElMessage.error(getErrorMessage(err, '上传失败'))
  } finally {
    avatarUploading.value = false
  }
}

function goPost(id) {
  router.push(`/post/${id}`)
}

async function toggleFollow() {
  if (!requireLogin(router)) return
  if (followingLoading.value) return
  followingLoading.value = true
  try {
    const { data } = await api.post(`/users/${userId.value}/follow`)
    profile.value.is_following = data.following
    profile.value.follower_count = data.follower_count
    ElMessage.success(data.following ? '关注成功' : '已取消关注')
  } catch (e) {
    ElMessage.error(getErrorMessage(e, '操作失败'))
  } finally {
    followingLoading.value = false
  }
}

function goMessage() {
  if (!requireLogin(router)) return
  router.push(`/messages/${userId.value}`)
}

async function blockUser() {
  if (!requireLogin(router)) return
  if (blockLoading.value) return
  blockLoading.value = true
  try {
    const { data } = await api.post(`/users/${userId.value}/block`)
    profile.value.is_blocked = data.blocked
    ElMessage.success(data.blocked ? '屏蔽成功' : '已取消屏蔽')
  } catch (e) {
    ElMessage.error(getErrorMessage(e, '操作失败'))
  } finally {
    blockLoading.value = false
  }
}

watch(
  () => route.params.id,
  () => {
    profile.value = null
    notFound.value = false
    loadError.value = ''
    load()
  },
)

onMounted(() => load())
</script>

<style scoped>
.page.profile-page {
  max-width: 720px;
}
.profile-head {
  display: flex;
  align-items: center;
  gap: 16px;
}
.avatar-text {
  background: #409eff;
  color: #fff;
  font-size: 28px;
  font-weight: 600;
}
.profile-info {
  min-width: 0;
}
.profile-name {
  font-size: 20px;
  font-weight: 600;
  color: #303133;
}
.profile-meta {
  font-size: 13px;
  color: #909399;
  margin-top: 4px;
}
.profile-bio {
  margin-top: 8px;
  font-size: 14px;
  color: #606266;
  white-space: pre-wrap;
  line-height: 1.6;
}
.profile-bio.muted {
  color: #c0c4cc;
}
.profile-actions {
  display: flex;
  gap: 8px;
  margin-top: 16px;
}
.profile-stats {
  display: flex;
  gap: 12px;
  margin-top: 20px;
}
.profile-stats .stat {
  flex: 1;
  text-align: center;
  background: #f5f7fa;
  border-radius: 8px;
  padding: 12px 0;
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.profile-stats a.stat {
  text-decoration: none;
  color: inherit;
  cursor: pointer;
  transition: background 0.2s;
}
.profile-stats a.stat:hover {
  background: #ecf5ff;
}
.stat-value {
  font-size: 20px;
  font-weight: 600;
  color: #303133;
}
.stat-label {
  font-size: 12px;
  color: #909399;
}
.edit-section {
  margin-top: 8px;
}
.edit-title {
  font-size: 15px;
  font-weight: 600;
  margin-bottom: 12px;
  color: #303133;
}
.edit-actions {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-top: 12px;
}
.form-status {
  font-size: 13px;
  color: #67c23a;
}
.form-status.error {
  color: #f56c6c;
}
.avatar-upload {
  margin-top: 16px;
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 14px;
  color: #606266;
}
.recent-empty {
  color: #909399;
  text-align: center;
  padding: 24px 0;
}
.recent-list {
  display: flex;
  flex-direction: column;
}
.recent-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 10px 0;
  border-bottom: 1px solid #f0f0f0;
}
.recent-item:last-child {
  border-bottom: none;
}
.recent-title {
  cursor: pointer;
  color: #303133;
  font-size: 14px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.recent-title:hover {
  color: #409eff;
}
.recent-time {
  color: #999;
  font-size: 12px;
  white-space: nowrap;
}
</style>
