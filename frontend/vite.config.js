import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// Vite 开发服务器：默认端口 5173
export default defineConfig({
  plugins: [vue()],
  server: {
    port: 5173,
    // 监听所有网卡（0.0.0.0），便于手机通过本机 IP 访问调试移动端。
    // 仅影响 dev server，不影响 npm run build 的构建产物与线上部署。
    host: true,
    // 本地开发：/api 与 /uploads 转发到本地后端（前端 baseURL 默认同源相对路径，
    // 无代理时 dev 5173 会把接口请求当 SPA 路由返回 index.html → Network Error）
    proxy: {
      '/api': { target: 'http://127.0.0.1:8081', changeOrigin: true },
      '/uploads': { target: 'http://127.0.0.1:8081', changeOrigin: true },
    },
  },
})
