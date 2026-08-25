import axios from 'axios'
import { ElMessage } from 'element-plus'
import { getToken, clearToken } from '../store/user'

// 后端 API 地址（Spring Boot 默认 8080）
const api = axios.create({
  baseURL: 'http://localhost:8080/api',
  timeout: 10000,
})

// 请求拦截器：有 token 时自动带上 Authorization: Bearer <token>
api.interceptors.request.use((config) => {
  const token = getToken()
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

let redirectingToLogin = false

// 未登录（401）统一处理：清除本地登录态并跳转登录页。
// 登录/注册接口自身的 401（如「用户名或密码错误」）不在此处理，由页面自己展示错误。
function handleUnauthorized() {
  clearToken()
  if (redirectingToLogin) return
  redirectingToLogin = true
  ElMessage.error('请先登录。')
  if (window.location.pathname !== '/login') {
    window.location.href = '/login'
    return
  }
  // 已经在登录页：不跳转，仅复位标志
  redirectingToLogin = false
}

// 响应拦截器：把后端返回的 { error: "..." } 暴露到 error.message，便于 UI 展示
api.interceptors.response.use(
  (response) => response,
  (error) => {
    const data = error.response?.data
    if (data && typeof data.error === 'string' && data.error) {
      error.message = data.error
    }
    if (error.response?.status === 401) {
      const url = error.config?.url || ''
      const isAuthEndpoint = /\/auth\/(login|register)(\/|$)/.test(url)
      if (!isAuthEndpoint) {
        handleUnauthorized()
      }
    }
    return Promise.reject(error)
  },
)

export default api
