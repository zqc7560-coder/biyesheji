/**
 * Vite 构建配置（M0 工程骨架）。
 *
 * 关键点：
 * 1. dev server 代理 /api → http://localhost:8080：开发期前端 5173、后端 8080 跨域，
 *    用 Vite 代理同源化，浏览器侧免 CORS 配置；生产由 Nginx 反代（部署文档 §1.2），
 *    两种环境的 /api 语义一致，业务代码里 baseURL 恒为 '/api' 不需要按环境切换。
 * 2. 别名 @ → src：全项目 import 统一 'src/...' 相对层级，避免 ../../ 地狱。
 * 3. vitest 的 test 块：环境用 node——本期被测逻辑是纯 JS（Axios 拦截器、Pinia store），
 *    不依赖 DOM，装 jsdom 是白付安装成本；页面组件测试留到 M10 按需再加。
 */
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { fileURLToPath, URL } from 'node:url'

export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url))
    }
  },
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true
      },
      // 上传目录静态资源也代理：开发期直接预览 /uploads/xxx.png
      '/uploads': {
        target: 'http://localhost:8080',
        changeOrigin: true
      }
    }
  },
  test: {
    environment: 'node',
    include: ['src/**/__tests__/**/*.spec.js']
  }
})
