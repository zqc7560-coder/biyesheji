<script setup>
/**
 * 用户中心壳（M3）：/user 从单页占位升级为带子导航的布局容器，
 * 子路由（我的文章/编辑器）经 <router-view/> 挂载。
 * M2 时代的资料编辑占位内容拆到 UserHomeView（默认子路由）。
 */
import { useRoute, useRouter } from 'vue-router'
const route = useRoute()
const router = useRouter()

const NAVS = [
  { path: '/user', label: '总览' },
  { path: '/user/articles', label: '我的文章' },
  { path: '/user/comments', label: '我的评论' },
  { path: '/user/articles/new', label: '✍ 写文章' },
  // M10：账号设置入口（UC-U-02/03 表单页；此前用户中心导航里根本没有它的出口）
  { path: '/user/settings', label: '账号设置' }
]
</script>

<template>
  <main class="uc">
    <nav class="uc-nav">
      <router-link v-for="n in NAVS" :key="n.path" :to="n.path"
                   :class="{ active: route.path === n.path }">{{ n.label }}</router-link>
    </nav>
    <router-view />
  </main>
</template>

<style scoped>
.uc { max-width: 1100px; margin: 24px auto; padding: 0 16px; }
.uc-nav { display: flex; gap: 18px; border-bottom: 1px solid #ebeef5; margin-bottom: 16px; }
.uc-nav a { text-decoration: none; color: #606266; padding: 8px 2px; font-size: 14px; }
.uc-nav a.active, .uc-nav a:hover { color: #409eff; border-bottom: 2px solid #409eff; }
</style>
