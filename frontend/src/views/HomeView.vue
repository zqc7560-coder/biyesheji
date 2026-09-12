<script setup>
/**
 * 公开首页/列表页（M3 落地版，替换 M0 健康检查占位）—— UC-V-01。
 * 筛选：分类/标签（URL query 驱动，可分享可回退）+ 关键词搜索（后端 LIKE content_text，
 * ngram FTS 升级在 M9 性能环节，接口契约不变）。
 * 后端 GET /api/articles 是公开接口（M3 SecurityConfig 放行只读三路径），游客直接可看。
 */
import { ref, reactive, onMounted, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { listArticles } from '@/api/article'

const route = useRoute()
const router = useRouter()

const data = reactive({ list: [], total: 0, pageNum: 1, pageSize: 10, pages: 0 })
const filters = reactive({
  categoryId: route.query.categoryId ? Number(route.query.categoryId) : null,
  tagId: route.query.tagId ? Number(route.query.tagId) : null,
  keyword: route.query.keyword || ''
})
const loading = ref(false)

async function fetchList(page = 1) {
  loading.value = true
  try {
    const res = await listArticles({
      pageNum: page,
      pageSize: data.pageSize,
      categoryId: filters.categoryId || undefined,
      tagId: filters.tagId || undefined,
      keyword: filters.keyword || undefined,
      sort: 'published_at,desc'
    })
    Object.assign(data, { list: res.list, total: res.total, pages: res.pages, pageNum: res.pageNum })
  } finally {
    loading.value = false
  }
}

/** 筛选变化同步回 URL：浏览器前进/后退与链接分享都以 query 为单一事实源 */
function syncQuery() {
  router.replace({
    query: {
      categoryId: filters.categoryId || undefined,
      tagId: filters.tagId || undefined,
      keyword: filters.keyword || undefined
    }
  })
  fetchList(1)
}

onMounted(() => fetchList(1))
watch(() => route.query, (q) => {
  // 支持从标签云/分类导航直接改 URL 进来（路由变化反向驱动筛选）
  filters.categoryId = q.categoryId ? Number(q.categoryId) : null
  filters.tagId = q.tagId ? Number(q.tagId) : null
  filters.keyword = q.keyword || ''
  fetchList(1)
})
</script>

<template>
  <main class="home">
    <h1 class="site-title">个人博客系统</h1>
    <div class="toolbar">
      <el-input v-model="filters.keyword" placeholder="搜索标题与正文…" clearable
                style="max-width: 320px" @keyup.enter="syncQuery" @clear="syncQuery">
        <template #append><el-button @click="syncQuery">搜索</el-button></template>
      </el-input>
    </div>

    <div v-loading="loading" class="card-list">
      <el-empty v-if="!data.list.length && !loading" description="还没有已发布的文章" />
      <router-link v-for="a in data.list" :key="a.id" :to="`/articles/${a.id}`" class="card">
        <img v-if="a.cover" :src="a.cover" class="cover" alt="" />
        <div class="card-main">
          <h2>{{ a.title }}</h2>
          <p class="summary">
            {{ a.summary || '（暂无摘要）' }}
            <el-tag v-if="a.summarySource === 1" size="small" type="primary" effect="plain">AI 生成</el-tag>
          </p>
          <div class="meta">
            <span>{{ a.author?.nickname }}</span>
            <span v-if="a.publishedAt">{{ String(a.publishedAt).slice(0, 10) }}</span>
            <span>{{ a.viewCount ?? 0 }} 浏览</span>
            <el-tag v-for="t in a.tags" :key="t.id" size="small" effect="plain">{{ t.name }}</el-tag>
          </div>
        </div>
      </router-link>
    </div>

    <el-pagination v-if="data.pages > 1" layout="prev, pager, next" :total="data.total"
                   :page-size="data.pageSize" :current-page="data.pageNum"
                   @current-change="fetchList" class="pager" />
  </main>
</template>

<style scoped>
.home { max-width: 860px; margin: 24px auto; padding: 0 16px; }
.site-title { font-size: 22px; margin-bottom: 16px; }
.toolbar { margin-bottom: 16px; }
.card-list { display: flex; flex-direction: column; gap: 14px; }
.card { display: flex; gap: 14px; border: 1px solid #ebeef5; border-radius: 8px; padding: 14px; text-decoration: none; color: inherit; background: #fff; transition: box-shadow .2s; }
.card:hover { box-shadow: 0 2px 12px rgba(0,0,0,.08); }
.cover { width: 140px; height: 92px; object-fit: cover; border-radius: 6px; flex-shrink: 0; }
.card-main { flex: 1; min-width: 0; }
.card-main h2 { font-size: 17px; margin: 0 0 6px; }
.summary { color: #606266; font-size: 13px; margin: 0 0 8px; display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden; }
.meta { color: #909399; font-size: 12px; display: flex; gap: 8px; align-items: center; flex-wrap: wrap; }
.pager { margin-top: 20px; justify-content: center; }
</style>
