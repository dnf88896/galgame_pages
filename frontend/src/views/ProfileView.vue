<template>
  <div class="page profile-page">
    <el-page-header content="个人主页" @back="() => goBack(router)" />

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
            <div class="profile-name">
              {{ profile.nickname || profile.username }}
              <span class="profile-username">@{{ profile.username }}</span>
              <el-tag v-if="isBannedProfile" size="small" type="danger" style="margin-left: 8px">该用户已被封禁</el-tag>
              <el-tag
                v-if="Number(profile.admin_level) > 0"
                size="small"
                type="danger"
                class="admin-tag"
              >管理员 Lv.{{ profile.admin_level }}</el-tag>
            </div>
            <div class="profile-meta">注册于 {{ formatTime(profile.created_at) }}</div>
            <div v-if="profile.bio" class="profile-bio">{{ profile.bio }}</div>
            <div v-else class="profile-bio muted">这个人很懒，还没有写签名。</div>
          </div>
          <!-- 萌点（积分）展示：资料卡右上角；签到按钮仅本人可见 -->
          <div class="moe-box">
            <div class="moe-title">✦ 萌点</div>
            <div class="moe-value">{{ profile.moe_points ?? 0 }}</div>
            <el-button
              v-if="isSelf"
              size="small"
              type="primary"
              :disabled="todayCheckedIn || checkingIn"
              :loading="checkingIn"
              @click="checkIn"
            >{{ todayCheckedIn ? '今日已签到' : '签到 +10' }}</el-button>
          </div>
        </div>

        <!-- 非本人资料页：关注 / 私聊 / 屏蔽 操作按钮 -->
        <div v-if="!isSelf" class="profile-actions">
          <el-button v-if="isAdmin && isBannedProfile" size="small" type="warning" @click="unbanUser">解封</el-button>
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
          <!-- 收藏入口：他人隐藏收藏时不展示，自己的资料页始终展示 -->
          <router-link
            v-if="isSelf || !Number(profile.hide_favorites)"
            :to="`/user/${userId}/favorites`"
            class="stat stat-link"
          >
            <span class="stat-value">{{ profile.favorite_count ?? 0 }}</span>
            <span class="stat-label">收藏</span>
          </router-link>
        </div>

        <!-- 自己的资料页：编辑签名 / 更换头像 -->
        <template v-if="isSelf">
          <el-divider />
          <div class="edit-section">
            <div class="edit-title">编辑资料</div>
            <div class="nickname-row">
              <span class="nickname-label">昵称：</span>
              <el-input
                v-model="nicknameDraft"
                maxlength="32"
                show-word-limit
                placeholder="帖子和评论上显示的名字（可随时修改）"
                style="max-width: 320px"
              />
              <el-button type="primary" :loading="nicknameSaving" @click="saveNickname">保存昵称</el-button>
            </div>
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
            <div class="hide-favorites-row">
              <span class="hide-favorites-label">隐藏我的收藏</span>
              <el-switch
                v-model="hideFavorites"
                :active-value="1"
                :inactive-value="0"
                @change="onHideFavoritesChange"
              />
            </div>
            <div class="password-row">
              <el-button size="small" @click="openPasswordDialog">修改密码</el-button>
            </div>
          </div>
        </template>
      </el-card>

      <el-dialog
        v-model="passwordDialogVisible"
        title="修改密码"
        width="90%"
        style="max-width: 420px"
        :close-on-click-modal="false"
        @closed="resetPasswordForm"
      >
        <el-form label-width="80px" @submit.prevent>
          <el-form-item label="旧密码">
            <el-input v-model="pwdForm.oldPassword" type="password" show-password placeholder="请输入当前密码" />
          </el-form-item>
          <el-form-item label="新密码">
            <el-input v-model="pwdForm.newPassword" type="password" show-password placeholder="至少 6 位" maxlength="72" />
          </el-form-item>
          <el-form-item label="确认新密码">
            <el-input v-model="pwdForm.confirmPassword" type="password" show-password placeholder="再次输入新密码" maxlength="72" />
          </el-form-item>
        </el-form>
        <template #footer>
          <el-button @click="passwordDialogVisible = false">取消</el-button>
          <el-button type="primary" :loading="passwordChanging" @click="changePassword">确认修改</el-button>
        </template>
      </el-dialog>

      <!-- 预留：后续可在此扩展更多用户板块（如 TA 的回复、收藏等） -->
      <el-card class="posts-card" style="margin-top: 16px">
        <template #header>最近发布</template>
        <div v-if="!profile.recent_posts || !profile.recent_posts.length" class="recent-empty">
          还没有发布过帖子。
        </div>
        <div v-else class="recent-list">
          <div v-for="p in profile.recent_posts" :key="p.id" class="recent-item">
            <span class="recent-title" @click="goPost(p.id)">{{ p.title }}</span>
            <span v-if="isPinned(p)" class="pinned-badge">置顶</span>
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
import { goBack } from '../utils/navigation'
import { user, setUser, requireLogin, clearToken } from '../store/user'
import { formatTime, resolveAssetUrl, getErrorMessage, isPinned } from '../utils/format'
import { refreshMoe } from '../utils/moeGain'

const route = useRoute()
const router = useRouter()

const userId = computed(() => Number(route.params.id))
const profile = ref(null)
const loading = ref(false)
const notFound = ref(false)
const loadError = ref('')

const bioDraft = ref('')
const nicknameDraft = ref('')
const nicknameSaving = ref(false)
// 隐藏收藏开关（1=隐藏，0=公开），初始值取登录用户信息里的 hide_favorites
const hideFavorites = ref(Number(user.value?.hide_favorites) === 1 ? 1 : 0)
const bioSaving = ref(false)
const editStatus = ref('')
const editStatusError = ref(false)
const avatarUploading = ref(false)
const followingLoading = ref(false)
const blockLoading = ref(false)
// 萌点签到：今日是否已签 + 签到请求中
const todayCheckedIn = ref(false)
const checkingIn = ref(false)

const passwordDialogVisible = ref(false)
const passwordChanging = ref(false)
const pwdForm = ref({ oldPassword: '', newPassword: '', confirmPassword: '' })

const isSelf = computed(() => !!user.value && Number(user.value.id) === Number(userId.value))
const avatarSrc = computed(() => resolveAssetUrl(profile.value?.avatar_url))
const firstChar = computed(() => (profile.value?.nickname || profile.value?.username || '?').slice(0, 1).toUpperCase())
const isAdmin = computed(() => !!user.value && Number(user.value.admin_level) > 0)
const isBannedProfile = computed(() => {
  const until = profile.value?.ban_until
  if (!until) return false
  const t = new Date(until).getTime()
  return !isNaN(t) && t > Date.now()
})

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
    if (isSelf.value) {
      bioDraft.value = data.bio || ''
      nicknameDraft.value = data.nickname || data.username || ''
      hideFavorites.value = Number(data.hide_favorites) === 1 ? 1 : 0
      await loadCheckInStatus()
    }
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

// 拉取本人今日签到状态（仅本人资料页调用；失败不影响资料展示）
async function loadCheckInStatus() {
  try {
    const { data } = await api.get('/check-in/status')
    todayCheckedIn.value = !!data.today_checked_in
    if (profile.value) profile.value.moe_points = data.moe_points
  } catch (e) {
    // 签到状态加载失败不阻断资料页
  }
}

// 每日签到：首次 +10 萌点；已签过提示
async function checkIn() {
  if (!requireLogin(router)) return
  if (checkingIn.value) return
  checkingIn.value = true
  try {
    const { data } = await api.post('/check-in')
    todayCheckedIn.value = true
    if (data.awarded) {
      ElMessage.success('签到成功，获得 10 萌点！')
      if (profile.value) profile.value.moe_points = data.moe_points
      // refreshMoe 检测增量弹「+n萌点」并同步 store/基线
      await refreshMoe(data.moe_points)
    } else {
      ElMessage.info('今天已经签到过啦。')
    }
  } catch (e) {
    ElMessage.error(getErrorMessage(e, '签到失败'))
  } finally {
    checkingIn.value = false
  }
}

async function saveNickname() {
  if (!isSelf.value) return
  const nickname = nicknameDraft.value.trim()
  if (!nickname) {
    ElMessage.warning('昵称不能为空。')
    return
  }
  nicknameSaving.value = true
  try {
    const { data } = await api.put('/auth/profile', {
      nickname,
      bio: bioDraft.value.trim(),
      hide_favorites: hideFavorites.value === 1 ? 'true' : 'false',
    })
    ElMessage.success('昵称已保存')
    profile.value.nickname = data.nickname
    if (user.value) {
      setUser({ ...user.value, nickname: data.nickname })
    }
  } catch (e) {
    ElMessage.error(getErrorMessage(e, '保存失败'))
  } finally {
    nicknameSaving.value = false
  }
}

async function saveBio() {
  if (!isSelf.value) return
  bioSaving.value = true
  editStatus.value = ''
  editStatusError.value = false
  try {
    // 注意：PUT /auth/profile 会整体覆盖 bio，必须把当前 hide_favorites 一并带上，避免互相覆盖
    const { data } = await api.put('/auth/profile', {
      bio: bioDraft.value.trim(),
      hide_favorites: hideFavorites.value === 1 ? 'true' : 'false',
    })
    ElMessage.success('签名已保存')
    profile.value.bio = data.bio
    hideFavorites.value = Number(data.hide_favorites) === 1 ? 1 : 0
    if (user.value) {
      setUser({ ...user.value, bio: data.bio, hide_favorites: hideFavorites.value })
    }
  } catch (e) {
    editStatus.value = getErrorMessage(e, '保存失败')
    editStatusError.value = true
  } finally {
    bioSaving.value = false
  }
}

// 隐藏收藏开关变更：同样带上当前 bioDraft，避免覆盖签名
async function onHideFavoritesChange(val) {
  // val 是切换后的新值（1/0），旧值即另一档
  const old = val === 1 ? 0 : 1
  try {
    const { data } = await api.put('/auth/profile', {
      bio: bioDraft.value.trim(),
      hide_favorites: val === 1 ? 'true' : 'false',
    })
    ElMessage.success('已更新')
    hideFavorites.value = Number(data.hide_favorites) === 1 ? 1 : 0
    profile.value.hide_favorites = hideFavorites.value
    if (user.value) {
      setUser({ ...user.value, hide_favorites: hideFavorites.value })
    }
  } catch (e) {
    // 失败回滚开关状态
    hideFavorites.value = old
    ElMessage.error(getErrorMessage(e, '更新失败'))
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

// 管理员解封（仅管理员且该用户被封禁时按钮可见）
async function unbanUser() {
  try {
    await api.post(`/users/${userId.value}/unban`)
    ElMessage.success('已解封')
    load()   // 重新加载主页信息
  } catch (e) {
    ElMessage.error(getErrorMessage(e, '解封失败'))
  }
}

function openPasswordDialog() {
  if (!requireLogin(router)) return
  passwordDialogVisible.value = true
}

function resetPasswordForm() {
  pwdForm.value = { oldPassword: '', newPassword: '', confirmPassword: '' }
}

async function changePassword() {
  const { oldPassword, newPassword, confirmPassword } = pwdForm.value
  if (!oldPassword || !newPassword || !confirmPassword) {
    ElMessage.warning('请填写完整。')
    return
  }
  if (newPassword.length < 6) {
    ElMessage.warning('新密码至少 6 位。')
    return
  }
  if (newPassword !== confirmPassword) {
    ElMessage.warning('两次输入的新密码不一致。')
    return
  }
  passwordChanging.value = true
  try {
    await api.put('/auth/password', {
      old_password: oldPassword,
      new_password: newPassword,
    })
    passwordDialogVisible.value = false
    ElMessage.success('密码已修改，请重新登录')
    clearToken()
    router.push('/login')
  } catch (e) {
    ElMessage.error(getErrorMessage(e, '修改失败'))
  } finally {
    passwordChanging.value = false
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
  flex-wrap: wrap;
  align-items: center;
  gap: 12px 16px;
}
.avatar-text {
  background: #409eff;
  color: #fff;
  font-size: 28px;
  font-weight: 600;
}
.moe-box {
  margin-left: auto;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 6px;
  background: #f5f7fa;
  border-radius: 8px;
  padding: 10px 16px;
  flex-shrink: 0;
}
.moe-title {
  font-size: 12px;
  color: #909399;
}
.moe-value {
  font-size: 20px;
  font-weight: 600;
  color: #e6a23c;
  line-height: 1;
}
.moe-box .el-button {
  margin-left: 0;
}
.profile-info {
  min-width: 0;
}
.profile-name {
  font-size: 20px;
  font-weight: 600;
  color: #303133;
}
.profile-username {
  font-size: 13px;
  color: #909399;
  margin-left: 6px;
  font-weight: 400;
}
.admin-tag {
  margin-left: 8px;
  vertical-align: middle;
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
  word-break: break-word;
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
  flex-wrap: wrap;
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
.nickname-row {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 16px;
  font-size: 14px;
  color: #606266;
}
.nickname-label {
  flex-shrink: 0;
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
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  font-size: 14px;
  color: #606266;
}
.avatar-upload input[type='file'] {
  max-width: 100%;
}
.hide-favorites-row {
  margin-top: 16px;
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 14px;
  color: #606266;
}
.password-row {
  margin-top: 16px;
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
/* 置顶帖子标签 */
.pinned-badge {
  font-size: 11px;
  color: #fff;
  background: #e6a23c;
  border-radius: 4px;
  padding: 2px 7px;
  line-height: 1.4;
  flex-shrink: 0;
}

/* ===== 移动端适配 ===== */
@media (max-width: 600px) {
  /* 6 个统计格挤不进一行，最后一个「收藏」入口会被卡片 overflow:hidden 裁掉 → 改 3 列 2 行 */
  .profile-stats {
    gap: 10px;
  }
  .profile-stats .stat {
    flex: 1 1 calc(33.333% - 7px);
  }
  /* 头像 + 信息 + 萌点框在窄屏挤成一团，让萌点框独占一行 */
  .profile-head .moe-box {
    margin-left: 0;
    width: 100%;
    flex-direction: row;
    justify-content: space-between;
  }
}
</style>
