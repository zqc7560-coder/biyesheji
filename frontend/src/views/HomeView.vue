<script setup>
/**
 * 公开首页/列表页（M4 升级版）—— UC-V-01 + UC-V-06。
 * 新增：左侧分类导航（GET /api/categories 树）+ 标签云（GET /api/tags?cloud=true）；
 * 筛选：分类/标签（URL query 驱动，可分享可回退）+ 关键词搜索。
 * 后端 GET /api/articles、/api/categories、/api/tags 均为公开接口（SecurityConfig M4 白名单），游客直接可看。
 * 标签云字号映射复用 tagCategory.js 的纯函数（已在 Vitest 锁死边界行为）。
 */
import { ref, reactive, computed, onMounted, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { listArticles } from '@/api/article'
import { fetchCategoryTree } from '@/api/category'
import { fetchTags } from '@/api/tag'
import { flattenCategoryTree, tagCloudFontSize } from '@/utils/tagCategory'

const route = useRoute()
const router = useRouter()

const data = reactive({ list: [], total: 0, pageNum: 1, pageSize: 10, pages: 0 })
const filters = reactive({
  categoryId: route.query.categoryId ? Number(route.query.categoryId) : null,
  tagId: route.query.tagId ? Number(route.query.tagId) : null,
  keyword: route.query.keyword || ''
})
const loading = ref(false)

/** M4：导航数据（分类树扁平化 + 标签云），与文章列表并行拉取、互不阻塞 */
const categoryOptions = ref([])
const cloudTags = ref([])
const activeTag = computed(() => filters.tagId
  ? cloudTags.value.find((t) => t.id === filters.tagId) : null)

onMounted(() => {
  fetchList(1)
  // 导航拉取失败不阻塞列表（降级：只显示文章卡片）——导航是辅助信息，不是主数据
  fetchCategoryTree().then((tree) => { categoryOptions.value = flattenCategoryTree(tree) }).catch(() => {})
  fetchTags(true).then((ts) => { cloudTags.value = ts }).catch(() => {})
})

/** 点分类/标签导航：改 query 后由下面的 route.query watcher 统一驱动 fetchList（单一事实源） */
function pickFilter(key, value) {
  const next = { ...route.query }
  if (value && next[key] !== String(value)) {
    next[key] = String(value)
  } else {
    delete next[key]   // 再点一次=取消选中（toggle 语义，比"只能换不能退"顺手）
  }
  router.replace({ query: next })
}

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
    <!-- M10：站点名/Logo/页脚备案号上移到 SiteLayout 全站顶栏（各公开页共享，
         HomeView 不再自渲染门面，避免"顶栏站点名 + 横幅站点名"双份出现） -->
    <div class="layout">
      <!-- M4：左侧导航（UC-V-06 分类导航 + 标签云）——数据来自公开接口，游客可用 -->
      <aside class="side">
        <section class="panel">
          <h3>分类</h3>
          <ul class="cat-list">
            <li v-for="c in categoryOptions" :key="c.id" class="cat-item"
                :class="{ active: filters.categoryId === c.id, child: c.depth > 0 }"
                @click="pickFilter('categoryId', c.id)">
              <span class="cat-name">{{ c.name }}</span>
              <span class="cat-count">{{ c.articleCount }}</span>
            </li>
          </ul>
        </section>
        <section class="panel">
          <h3>标签云</h3>
          <div class="cloud">
            <span v-for="t in cloudTags" :key="t.id" class="cloud-tag"
                  :class="{ active: filters.tagId === t.id }"
                  :style="{ fontSize: tagCloudFontSize(t.weight), color: t.color }"
                  @click="pickFilter('tagId', t.id)">{{ t.name }}</span>
            <el-empty v-if="!cloudTags.length" :image-size="40" description="暂无标签" />
          </div>
        </section>
      </aside>

      <section class="content">
    <div class="toolbar">
      <el-input v-model="filters.keyword" placeholder="搜索标题与正文…" clearable
                style="max-width: 320px" @keyup.enter="syncQuery" @clear="syncQuery">
        <template #append><el-button @click="syncQuery">搜索</el-button></template>
      </el-input>
      <!-- 当前生效的筛选态可视化：让"列表为什么只有这些"一目了然，点 × 即清除 -->
      <el-tag v-if="filters.categoryId" closable class="active-filter"
              @close="pickFilter('categoryId', filters.categoryId)">
        分类：{{ categoryOptions.find(c => c.id === filters.categoryId)?.name || filters.categoryId }}
      </el-tag>
      <el-tag v-if="activeTag" closable class="active-filter" @close="pickFilter('tagId', activeTag.id)">
        标签：{{ activeTag.name }}
      </el-tag>
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
      </section>
    </div>
    <!-- M10：页脚备案号归 SiteLayout 统一渲染（site_icp 为空则整行不渲染，避免"空壳合规位"） -->
  </main>
</template>

<style scoped>
.home { max-width: 1080px; margin: 24px auto; padding: 0 16px; }
.site-title { font-size: 22px; margin-bottom: 16px; }
/* M8：顶栏 Logo（object-fit contain 防拉伸变形）与页脚备案号 */
.site-logo { height: 26px; max-width: 90px; object-fit: contain; vertical-align: middle; margin-right: 8px; }
.site-foot { margin-top: 24px; text-align: center; color: #909399; font-size: 12px; }
/* M4：两栏布局——窄屏（<768px）导航折到列表上方，保持移动端可读 */
.layout { display: flex; gap: 20px; align-items: flex-start; }
.side { width: 200px; flex-shrink: 0; display: flex; flex-direction: column; gap: 14px; position: sticky; top: 16px; }
.content { flex: 1; min-width: 0; }
.panel { border: 1px solid #ebeef5; border-radius: 8px; background: #fff; padding: 12px; }
.panel h3 { font-size: 14px; margin: 0 0 8px; color: #303133; }
.cat-list { list-style: none; margin: 0; padding: 0; }
.cat-item { display: flex; justify-content: space-between; padding: 5px 8px; border-radius: 5px; cursor: pointer; font-size: 13px; color: #606266; }
.cat-item.child { padding-left: 20px; font-size: 12px; }
.cat-item:hover { background: #f5f7fa; }
.cat-item.active { background: #ecf5ff; color: #409eff; }
.cat-count { color: #c0c4cc; font-size: 12px; }
.cloud { display: flex; flex-wrap: wrap; gap: 6px 10px; align-items: baseline; padding: 4px 2px; }
.cloud-tag { cursor: pointer; line-height: 1.4; opacity: .85; }
.cloud-tag:hover { opacity: 1; text-decoration: underline; }
.cloud-tag.active { font-weight: 700; text-decoration: underline; }
.toolbar { margin-bottom: 16px; display: flex; gap: 10px; align-items: center; flex-wrap: wrap; }
.active-filter { cursor: pointer; }
.card-list { display: flex; flex-direction: column; gap: 14px; }
.card { display: flex; gap: 14px; border: 1px solid #ebeef5; border-radius: 8px; padding: 14px; text-decoration: none; color: inherit; background: #fff; transition: box-shadow .2s; }
.card:hover { box-shadow: 0 2px 12px rgba(0,0,0,.08); }
.cover { width: 140px; height: 92px; object-fit: cover; border-radius: 6px; flex-shrink: 0; }
.card-main { flex: 1; min-width: 0; }
.card-main h2 { font-size: 17px; margin: 0 0 6px; }
.summary { color: #606266; font-size: 13px; margin: 0 0 8px; display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden; }
.meta { color: #909399; font-size: 12px; display: flex; gap: 8px; align-items: center; flex-wrap: wrap; }
.pager { margin-top: 20px; justify-content: center; }
@media (max-width: 768px) {
  .layout { flex-direction: column; }
  .side { width: 100%; position: static; flex-direction: row; }
  .panel { flex: 1; }
}
</style>
