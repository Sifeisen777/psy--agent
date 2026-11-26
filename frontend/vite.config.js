import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// 后端默认端口随环境变（本机 8080 被占用时常用 18080）
// 用 VITE_API_TARGET 覆盖：VITE_API_TARGET=http://localhost:18080 npm run dev
export default defineConfig({
  plugins: [vue()],
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: process.env.VITE_API_TARGET || 'http://localhost:18080',
        changeOrigin: true
      }
    }
  }
})
