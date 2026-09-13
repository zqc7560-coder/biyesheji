<script setup>
/**
 * 归档页（M10 建立，M15 体验打磨）—— UC-V-06「按年/月归档浏览」的前端半边。
 * 数据口：GET /api/articles/archive（公开，返回 {"2026":{"09":[{id,title,publishedAt}]}}）。
 * 整形：groupArchive 纯函数（utils/archive.js，已 Vitest）——显式年月降序 + 非法键过滤。
 *
 * M15 新增（归档体验打磨）：
 * · 本地即时过滤（filterArchive 纯函数）：全量数据已在手上，再跳后端搜索是绕远路；
 * · 命中数徽标 + 未命中 el-empty 文案区分（"没有文章"和"没搜到"是两回事）；
 * · 移动端：月份标签与条目改为纵向堆叠，日号与标题同行不换行挤位。
 */
import { ref, computed, onMounted } from 'vue'
import { fetchArchive } from '@/api/article'
import { groupArchive, monthLabel, filterArchive } from '@/utils/archive'

const sections = ref([])
const loading = ref(true)
/** 本地过滤词（页面态，不进 URL——归档过滤是临时视图筛选，分享价值低） */
const filterKw = ref('')

/** 渲染列表 = 过滤纯函数投影（filterKw 空时等价全量浅拷贝，见 filterArchive 契约） */
const shown = computed(() => filterArchive(sections.value, filterKw.value))
const shownTotal = computed(() => shown.value.reduce((s, y) => s + y.total, 0))
const grandTotal = computed(() => sections.value.reduce((s, y) => s + y.total, 0))

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
    <div class="head">
      <h2>文章归档 <small v-if="grandTotal">共 {{ grandTotal }} 篇</small></h2>
      <!-- M15：本地过滤框（即时生效，无按钮无请求） -->
      <el-input v-model="filterKw" placeholder="按标题过滤…" clearable size="small" class="filter-input" />
    </div>
    <el-empty v-if="!shown.length && !loading"
              :description="filterKw ? `没有标题包含「${filterKw}」的归档文章` : '还没有已发布的文章'" />
    <p v-if="filterKw && shown.length" class="hit-hint">匹配 {{ shownTotal }} / {{ grandTotal }} 篇</p>
    <section v-for="y in shown" :key="y.year" class="year">
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
/* M15：页头行（标题+过滤框同行，窄屏换行） */
.head { display: flex; align-items: baseline; gap: 12px; flex-wrap: wrap; }
.head h2 { margin: 0; }
.head h2 small { color: #909399; font-weight: 400; font-size: 13px; }
.filter-input { max-width: 220px; }
.hit-hint { color: #909399; font-size: 12px; margin: 8px 0 0; }
.year h3 { border-bottom: 2px solid #409eff; padding-bottom: 4px; }
.year h3 small { color: #909399; font-weight: 400; }
.month { display: flex; gap: 12px; margin: 10px 0; }
.m-title { width: 44px; flex-shrink: 0; color: #909399; font-size: 13px; padding-top: 2px; }
.items { list-style: none; margin: 0; padding: 0; flex: 1; }
.items li { padding: 3px 0; display: flex; gap: 8px; }
.day { color: #c0c4cc; font-size: 13px; font-variant-numeric: tabular-nums; }
.items a { color: #303133; text-decoration: none; }
.items a:hover { color: #409eff; }
/* M15 移动端断点系统回归：过滤框拉满一行；月份标签挪到条目上方成小标题，
   条目日号+标题同行保持扫读节奏（手机竖屏 44px 左列纯属浪费宽度） */
@media (max-width: 768px) {
  .archive { margin: 16px auto; }
  .filter-input { max-width: none; width: 100%; }
  .month { flex-direction: column; gap: 2px; margin: 12px 0; }
  .m-title { width: auto; font-weight: 600; color: #606266; }
}
</style>
