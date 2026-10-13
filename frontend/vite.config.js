import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  server: {
    port: 5173,
    proxy: {
      // 开发环境将接口与上传图片请求代理到后端，避免跨域
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
        // SSE 流式必须关掉代理层的缓冲，否则 /ai/chat/stream 的增量会被
        // http-proxy 攒成一坨再吐给前端，表现为"打字机效果"整个消失。
        configure: (proxy) => {
          proxy.on('proxyRes', (proxyRes) => {
            if ((proxyRes.headers['content-type'] || '').includes('text/event-stream')) {
              proxyRes.headers['x-accel-buffering'] = 'no'
            }
          })
        }
      },
      '/uploads': 'http://localhost:8080'
    }
  }
})
