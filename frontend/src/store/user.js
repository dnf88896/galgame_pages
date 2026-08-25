import { ref } from 'vue'
import { ElMessage } from 'element-plus'

const TOKEN_KEY = 'galforum_token'
const USER_KEY = 'galforum_user'

function readUser() {
  try {
    const raw = localStorage.getItem(USER_KEY)
    return raw ? JSON.parse(raw) : null
  } catch {
    return null
  }
}

// 响应式登录态单例（无额外依赖）
export const token = ref(localStorage.getItem(TOKEN_KEY) || '')
export const user = ref(readUser())

export function getToken() {
  return token.value
}

export function setToken(value) {
  token.value = value
  if (value) {
    localStorage.setItem(TOKEN_KEY, value)
  } else {
    localStorage.removeItem(TOKEN_KEY)
  }
}

export function setUser(u) {
  user.value = u
  if (u) {
    localStorage.setItem(USER_KEY, JSON.stringify(u))
  } else {
    localStorage.removeItem(USER_KEY)
  }
}

export function clearToken() {
  token.value = ''
  localStorage.removeItem(TOKEN_KEY)
  localStorage.removeItem(USER_KEY)
  user.value = null
}

export function isLoggedIn() {
  return !!token.value
}

// 未登录时提示并跳转登录页；已登录返回 true。
// redirect 为登录成功后的回跳地址（默认当前页面）。
export function requireLogin(router, redirect = null) {
  if (isLoggedIn()) return true
  ElMessage.warning('请先登录。')
  router.push({
    path: '/login',
    query: { redirect: redirect || router.currentRoute.value.fullPath },
  })
  return false
}
