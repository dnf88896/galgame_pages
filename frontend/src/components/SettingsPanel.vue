<template>
  <el-drawer
    :model-value="modelValue"
    @update:model-value="onUpdate"
    size="380px"
    title="设置"
  >
    <div class="settings-panel">
      <el-tabs v-model="activeTab">
        <el-tab-pane label="外观" name="appearance">
          <div class="setting-item">
            <div class="setting-label">深浅色模式</div>
            <el-radio-group :model-value="settings.theme" @change="setTheme">
              <el-radio-button value="light">浅色</el-radio-button>
              <el-radio-button value="dark">深色</el-radio-button>
            </el-radio-group>
          </div>

          <div class="setting-item">
            <div class="setting-label">页面透明度 <span class="setting-value">{{ settings.pageOpacity }}%</span></div>
            <el-slider
              v-model="settings.pageOpacity"
              :min="60"
              :max="100"
              :step="1"
              @input="apply()"
            />
            <div class="setting-desc">调低可透出背景</div>
          </div>

          <div class="setting-item">
            <div class="setting-label">背景亮度 <span class="setting-value">{{ settings.bgBrightness }}%</span></div>
            <el-slider
              v-model="settings.bgBrightness"
              :min="40"
              :max="150"
              :step="5"
              @input="apply()"
            />
            <div class="setting-desc">低于 100 变暗，高于 100 变亮</div>
          </div>
        </el-tab-pane>

        <el-tab-pane label="背景" name="background">
          <div v-if="settings.bgImage" class="bg-preview-wrap">
            <img :src="settings.bgImage" alt="当前背景" class="bg-preview" />
            <el-button size="small" @click="removeBg">移除背景</el-button>
          </div>

          <div class="setting-item">
            <el-button type="primary" @click="pickFile">上传背景图片</el-button>
            <input
              ref="fileInput"
              type="file"
              accept="image/*"
              style="display: none"
              @change="onFileChange"
            />
          </div>

          <div class="setting-desc">背景图片仅保存在本浏览器（localStorage），换设备/换浏览器不保留</div>
        </el-tab-pane>

        <el-tab-pane label="管理员" name="admin">
          <div v-if="!isLoggedIn()" class="setting-item">
            <div class="setting-desc">登录后可使用管理员认证</div>
            <el-button class="admin-login-btn" type="primary" @click="goLogin">去登录</el-button>
          </div>

          <template v-else>
            <div class="setting-item">
              <div class="setting-label">当前权限等级</div>
              <el-tag :type="currentLevel > 0 ? 'danger' : 'info'">Lv.{{ currentLevel }}</el-tag>
              <div class="setting-desc">初始为 0，输入管理员密码可提升等级</div>
            </div>

            <div class="setting-item">
              <div class="setting-label">管理员认证</div>
              <el-input
                v-model="adminPassword"
                type="password"
                show-password
                placeholder="请输入管理员权限密码"
                @keyup.enter="submitAdminVerify"
              />
              <div class="admin-verify-row">
                <el-button type="primary" :loading="adminVerifying" @click="submitAdminVerify">认证</el-button>
                <span class="form-status" :class="{ error: adminStatusError }">{{ adminStatus }}</span>
              </div>
            </div>
          </template>
        </el-tab-pane>
      </el-tabs>
    </div>
  </el-drawer>
</template>

<script setup>
import { ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import api from '../api'
import { settings, apply, setTheme, setBgImage } from '../store/settings'
import { user, setUser, isLoggedIn } from '../store/user'
import { getErrorMessage } from '../utils/format'

const props = defineProps({
  modelValue: Boolean,
})
const emit = defineEmits(['update:modelValue'])

const activeTab = ref('appearance')
const fileInput = ref(null)

const route = useRoute()
const router = useRouter()

const currentLevel = ref(user.value?.admin_level ?? 0)
const adminPassword = ref('')
const adminVerifying = ref(false)
const adminStatus = ref('')
const adminStatusError = ref(false)

// 打开面板时同步最新用户信息（含 admin_level）
watch(
  () => props.modelValue,
  (open) => {
    if (open && isLoggedIn()) syncFromMe()
  },
)

async function syncFromMe() {
  try {
    const { data } = await api.get('/auth/me')
    setUser(data)
    currentLevel.value = data.admin_level ?? 0
  } catch {
    // 401 等错误静默忽略，不弹窗
  }
}

// 未登录 → 跳登录页，登录成功回跳当前页面
function goLogin() {
  router.push({ path: '/login', query: { redirect: route.fullPath } })
}

async function submitAdminVerify() {
  const password = adminPassword.value
  if (!password) {
    adminStatus.value = '请输入管理员密码。'
    adminStatusError.value = true
    return
  }
  adminVerifying.value = true
  adminStatus.value = ''
  adminStatusError.value = false
  try {
    const { data } = await api.post('/auth/admin-verify', { password })
    setUser(data)
    currentLevel.value = data.admin_level ?? 0
    adminPassword.value = ''
    const successMsg = '认证成功，当前权限等级 Lv.' + currentLevel.value
    adminStatus.value = successMsg
    adminStatusError.value = false
    ElMessage.success(successMsg)
  } catch (e) {
    const msg = getErrorMessage(e, '认证失败')
    adminStatus.value = msg
    adminStatusError.value = true
    ElMessage.error(msg)
  } finally {
    adminVerifying.value = false
  }
}

function onUpdate(val) {
  emit('update:modelValue', val)
}

function pickFile() {
  fileInput.value && fileInput.value.click()
}

function removeBg() {
  setBgImage('')
  ElMessage.success('背景已移除')
}

function onFileChange(e) {
  const file = e.target.files && e.target.files[0]
  // 每次清空 value，允许连续选同一张图
  e.target.value = ''
  if (!file) return

  const reader = new FileReader()
  reader.onload = () => {
    const img = new Image()
    img.onload = () => {
      // 最长边上限 1920px，不足则保持原尺寸
      const max = 1920
      const longest = Math.max(img.width, img.height)
      const scale = longest > max ? max / longest : 1
      const canvas = document.createElement('canvas')
      canvas.width = Math.max(1, Math.round(img.width * scale))
      canvas.height = Math.max(1, Math.round(img.height * scale))
      const ctx = canvas.getContext('2d')
      ctx.drawImage(img, 0, 0, canvas.width, canvas.height)
      const dataUrl = canvas.toDataURL('image/jpeg', 0.75)
      if (dataUrl.length > 3 * 1024 * 1024) {
        ElMessage.error('图片过大，请换一张较小的图片')
        return
      }
      setBgImage(dataUrl)
      ElMessage.success('背景已更新')
    }
    img.onerror = () => ElMessage.error('图片读取失败')
    img.src = reader.result
  }
  reader.onerror = () => ElMessage.error('图片读取失败')
  reader.readAsDataURL(file)
}
</script>

<style scoped>
/* el-drawer 内容被 teleport 到 body，作用到 drawer 内部需用 :deep() */
:deep(.el-drawer__body) {
  padding: 16px 20px 20px;
}

.setting-item {
  margin-bottom: 22px;
}

.setting-item:last-child {
  margin-bottom: 0;
}

.setting-label {
  font-size: 14px;
  color: var(--el-text-color-primary);
  font-weight: 500;
  margin-bottom: 8px;
}

.setting-value {
  font-weight: 400;
  color: var(--el-text-color-secondary);
  font-size: 13px;
}

.setting-desc {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  margin-top: 4px;
  line-height: 1.5;
}

.form-status {
  font-size: 13px;
  color: #67c23a;
}

.form-status.error {
  color: #f56c6c;
}

.admin-verify-row {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-top: 12px;
}

.admin-login-btn {
  margin-top: 12px;
}

.bg-preview-wrap {
  margin-bottom: 20px;
}

.bg-preview {
  display: block;
  max-width: 100%;
  max-height: 120px;
  border-radius: 8px;
  border: 1px solid var(--el-border-color);
  margin-bottom: 10px;
  object-fit: cover;
}
</style>
