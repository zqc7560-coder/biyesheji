<script setup>
/**
 * 后台-仪表盘占位（M2）：/admin 默认子路由，让布局框架"有内容可渲染"。
 * 真实聚合数据（文章/用户/评论数、7日PV、AI 调用）对接 GET /api/admin/dashboard，
 * 该接口依赖 M3/M5 的数据面，占位页先立结构（避免 M10 再动路由）。
 */
import { useUserStore } from '@/stores/user'

const store = useUserStore()
</script>

<template>
  <div>
    <h2>仪表盘</h2>
    <el-alert type="info" :closable="false"
              :title="`你好，${store.user?.nickname || store.user?.username}。聚合统计在 M3/M5 数据面就绪后接入 /api/admin/dashboard。`" />
  </div>
</template>
