<script setup>
/**
 * 归档页（M10）—— UC-V-06「按年/月归档浏览」的前端半边（台账 M3/M4 顺延项在此闭环）。
 * 数据口：GET /api/articles/archive（公开，返回 {"2026":{"09":[{id,title,publishedAt}]}}）。
 * 整形：groupArchive 纯函数（utils/archive.js，已 Vitest）——显式年月降序 + 非法键过滤。
 */
import { ref, onMounted } from 'vue'
import { fetchArchive } from '@/api/article'
import { groupArchive, monthLabel } from '@/utils/archive'

const sections = ref([])
const loading = ref(true)

onMounted(async () => {
  try {
    sections.value = groupArchive(await fetchArchive())
  } finally {
    loading.value = false
  }
})
</script>

<template>
  <div class="archive" v-loading="loading">
    <h2>文章归档</h2>
    <el-empty v-if="!sections.length && !loading" description="还没有已发布的文章" />
    <section v-for="y in sections" :key="y.year" class="year">
      <h3>{{ y.year }} <small>（{{ y.total }} 篇）</small></h3>
      <div v-for="m in y.months" :key="m.month" class="month">
        <div class="m-title">{{ monthLabel(m.month) }}</div>
        <ul class="items">
          <li v-for="a in m.items" :key="a.id">
            <span class="day">{{ String(a.publishedAt || '').slice(8, 10).padStart(2, '0') }}</span>
            <router-link :to="`/articles/${a.id}`">{{ a.title }}</router-link>
          </li>
        </ul>
      </div>
    </section>
  </div>
</template>

<style scoped>
.archive { max-width: 760px; margin: 24px auto; padding: 0 16px; }
.year h3 { border-bottom: 2px solid #409eff; padding-bottom: 4px; }
.year h3 small { color: #909399; font-weight: 400; }
.month { display: flex; gap: 12px; margin: 10px 0; }
.m-title { width: 44px; flex-shrink: 0; color: #909399; font-size: 13px; padding-top: 2px; }
.items { list-style: none; margin: 0; padding: 0; flex: 1; }
.items li { padding: 3px 0; display: flex; gap: 8px; }
.day { color: #c0c4cc; font-size: 13px; font-variant-numeric: tabular-nums; }
.items a { color: #303133; text-decoration: none; }
.items a:hover { color: #409eff; }
</style>
