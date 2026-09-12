<script setup>
/**
 * 首页占位（M0）：调一次 GET /api/health 展示后端链路连通态——
 * 阶段二验收项"一条 /api/health 贯通"的前端半边；M10 替换为文章列表。
 */
import { ref, onMounted } from 'vue'
import request from '@/api/request'

const health = ref(null)
const error = ref('')

onMounted(async () => {
  try {
    health.value = await request.get('/health') // 拦截器已拆信封，直接拿 data
  } catch (e) {
    error.value = e.message || '后端未就绪'
  }
})
</script>

<template>
  <main style="max-width: 720px; margin: 80px auto; padding: 0 16px">
    <h1>个人博客系统</h1>
    <p>毕业设计 · Vue3 + Spring Boot 3 前后端分离（M0 工程骨架）</p>
    <el-alert v-if="health" type="success" :closable="false"
              :title="`后端链路正常：MySQL=${health.mysql} Redis=${health.redis}`" />
    <el-alert v-else-if="error" type="warning" :closable="false"
              :title="`后端未连通：${error}（请先启动 blog-server）`" />
  </main>
</template>
