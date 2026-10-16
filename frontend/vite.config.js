import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import AutoImport from 'unplugin-auto-import/vite'
import Components from 'unplugin-vue-components/vite'
import { ElementPlusResolver } from 'unplugin-vue-components/resolvers'

// 后端地址：开发环境所有 /api 与 /uploads 请求都转发到这里。
// 部署时改成你的服务器域名即可，调用方无需改。
// 后端地址：开发环境所有 /api 与 /uploads 请求都转发到这里。
// 用 127.0.0.1 而不是 localhost：localhost 会解析出 ::1 和 127.0.0.1，
// ::1 排在前面，若后端只监听 IPv4 就会间歇性连不上（同样的坑 ai.service.url 也踩过）。
const BACKEND = 'http://127.0.0.1:8080'

export default defineConfig({
  plugins: [
    vue(),
    // Element Plus 按需引入：用到的 <el-*> 标签和 ElMessage / ElMessageBox 自动注册，
    // 没用到的不打包。从 940KB vendor-element 砍到只装实际用到的组件（实测 ~80KB）。
    AutoImport({ resolvers: [ElementPlusResolver()] }),
    Components({ resolvers: [ElementPlusResolver()] })
  ],
  server: {
    port: 5173,
    proxy: {
      // 开发环境将接口与上传图片请求代理到后端，避免跨域
      '/api': {
        target: BACKEND,
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
      '/uploads': BACKEND
    }
  },
  build: {
    // 单包预警阈值提到 1.5MB，配合下方 manualChunks 让主包维持在 600KB 以下。
    // 视图懒加载已经在 router.js 里做（每个路由都是独立 chunk），所以真正大的就是
    // vue + element-plus + axios 这些 vendor；manualChunks 把它们各自拆出来。
    chunkSizeWarningLimit: 1500,
    rollupOptions: {
      output: {
        manualChunks: {
          // Vue 运行时 + 编译器 + 路由
          'vendor-vue': ['vue', 'vue-router'],
          // axios + 我们自己的小工具
          'vendor-axios': ['axios']
          // 注意：不再单独拆 vendor-element，按需引入后首屏用到的组件自动 inline 进所属 chunk
        }
      }
    }
  }
})
