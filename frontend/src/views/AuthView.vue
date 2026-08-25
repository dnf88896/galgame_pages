<template>
  <div class="auth-page">
    <el-card class="auth-card">
      <el-tabs v-model="activeTab" stretch>
        <el-tab-pane label="登录" name="login">
          <el-form label-position="top" @submit.prevent>
            <el-form-item label="用户名">
              <el-input
                v-model="loginForm.username"
                placeholder="请输入用户名"
                maxlength="20"
                clearable
              />
            </el-form-item>
            <el-form-item label="密码">
              <el-input
                v-model="loginForm.password"
                type="password"
                placeholder="请输入密码"
                show-password
                @keyup.enter="doLogin"
              />
            </el-form-item>
            <el-button type="primary" class="w-full" :loading="submitting" @click="doLogin">
              登录
            </el-button>
            <div class="auth-status" :class="{ error: statusError }">{{ status }}</div>
          </el-form>
        </el-tab-pane>

        <el-tab-pane label="注册" name="register">
          <el-form label-position="top" @submit.prevent>
            <el-form-item label="用户名">
              <el-input
                v-model="regForm.username"
                placeholder="2~20 位中英文、数字或下划线"
                maxlength="20"
                clearable
              />
            </el-form-item>
            <el-form-item label="密码">
              <el-input
                v-model="regForm.password"
                type="password"
                placeholder="至少 6 位"
                show-password
              />
            </el-form-item>
            <el-form-item label="确认密码">
              <el-input
                v-model="regForm.confirm"
                type="password"
                placeholder="再次输入密码"
                show-password
                @keyup.enter="doRegister"
              />
            </el-form-item>
            <el-button type="primary" class="w-full" :loading="submitting" @click="doRegister">
              注册
            </el-button>
            <div class="auth-status" :class="{ error: statusError }">{{ status }}</div>
          </el-form>
        </el-tab-pane>
      </el-tabs>
    </el-card>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import api from '../api'
import { setToken, setUser, isLoggedIn } from '../store/user'
import { getErrorMessage } from '../utils/format'

const router = useRouter()
const route = useRoute()

const activeTab = ref('login')
const loginForm = reactive({ username: '', password: '' })
const regForm = reactive({ username: '', password: '', confirm: '' })

// 登录成功后的回跳地址（取 ?redirect=，非字符串则回首页）
const redirectTarget = computed(() => {
  const r = route.query.redirect
  return typeof r === 'string' && r ? r : '/'
})

const submitting = ref(false)
const status = ref('')
const statusError = ref(false)

const usernameRe = /^[一-龥A-Za-z0-9_]{2,20}$/

// 已有登录态访问 /login：直接回首页（或原跳转目标）
onMounted(() => {
  if (isLoggedIn()) {
    router.replace(redirectTarget.value)
  }
})

async function doLogin() {
  const username = loginForm.username.trim()
  const password = loginForm.password
  if (!username || !password) {
    status.value = '请输入用户名和密码。'
    statusError.value = true
    return
  }
  submitting.value = true
  status.value = ''
  statusError.value = false
  try {
    const { data } = await api.post('/auth/login', { username, password })
    setToken(data.token)
    setUser(data.user)
    ElMessage.success('登录成功')
    router.replace(redirectTarget.value)
  } catch (e) {
    const msg = getErrorMessage(e, '登录失败')
    status.value = msg
    statusError.value = true
  } finally {
    submitting.value = false
  }
}

async function doRegister() {
  const username = regForm.username.trim()
  const password = regForm.password
  if (!usernameRe.test(username)) {
    status.value = '用户名需为 2~20 位中英文、数字或下划线。'
    statusError.value = true
    return
  }
  if (password.length < 6) {
    status.value = '密码至少 6 位。'
    statusError.value = true
    return
  }
  if (password !== regForm.confirm) {
    status.value = '两次输入的密码不一致。'
    statusError.value = true
    return
  }
  submitting.value = true
  status.value = ''
  statusError.value = false
  try {
    const { data } = await api.post('/auth/register', { username, password })
    setToken(data.token)
    setUser(data.user)
    ElMessage.success('注册成功')
    router.replace(redirectTarget.value)
  } catch (e) {
    const msg = getErrorMessage(e, '注册失败')
    status.value = msg
    statusError.value = true
  } finally {
    submitting.value = false
  }
}
</script>

<style scoped>
.auth-page {
  display: flex;
  justify-content: center;
  padding: 48px 16px;
}
.auth-card {
  width: 100%;
  max-width: 400px;
}
.w-full {
  width: 100%;
}
.auth-status {
  margin-top: 12px;
  font-size: 13px;
  color: #67c23a;
  text-align: center;
  min-height: 20px;
}
.auth-status.error {
  color: #f56c6c;
}
</style>
