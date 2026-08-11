import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// 后端地址可用环境变量覆盖（如本机 8080 被占用时：VITE_PROXY_TARGET=http://127.0.0.1:18080 npm run dev）
// 默认对齐全项目约定端口 18080（见 README 快速开始）
const proxyTarget = process.env.VITE_PROXY_TARGET || 'http://127.0.0.1:18080'

export default defineConfig({
  plugins: [vue()],
  server: {
    host: '127.0.0.1',
    port: 5173,
    proxy: {
      '/api': {
        target: proxyTarget,
        changeOrigin: true,
      },
    },
  },
})
