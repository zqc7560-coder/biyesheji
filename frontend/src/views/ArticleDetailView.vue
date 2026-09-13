<script setup>
/**
 * 文章详情页（M3）—— UC-V-02 的渲染端实现。
 *
 * 渲染管线（NFR-05 前端半边）：contentMd → renderMarkdown（markdown-it html:false
 * + 协议白名单 + DOMPurify）→ v-html。唯一可信来源是净化后的字符串，
 * 正文里混写的原始 HTML 在第一道闸就被转义。
 *
 * TOC：extractToc 扫 h2/h3 → injectTocIds 回写锚点 id → 点击 scrollIntoView。
 * M9 接入 Redis 详情缓存后本组件零改动（契约不变，只是响应快）。
 */
import { ref, computed, onMounted, watch, nextTick } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { fetchArticle, changeArticleStatus, deleteArticle } from '@/api/article'
import CommentSection from '@/views/CommentSection.vue'
import { renderMarkdown, extractToc, injectTocIds } from '@/utils/markdown'
import { useUserStore } from '@/stores/user'
import { ElMessage, ElMessageBox } from 'element-plus'
// M15：代码高亮主题（lightweight github 风）——随本路由分包进 chunk，
// 不放 main.js：只有详情页消费 pre.hljs 结构，登录页/列表页为它买单不值。
// 管道侧（utils/markdown.js highlightCode）产出 span.hljs-*，配色全在这份 CSS。
import 'highlight.js/styles/github.min.css'

const route = useRoute()
const router = useRouter()
const store = useUserStore()

const article = ref(null)
const loading = ref(true)
const error = ref('')

/** 净化后的正文 HTML 与 TOC（一次渲染两处消费，避免双解析漂移） */
const rendered = computed(() => {
  if (!article.value?.contentMd) return { html: '', toc: [] }
  const raw = renderMarkdown(article.value.contentMd)
  const toc = extractToc(raw)
  return { html: injectTocIds(raw, toc), toc }
})

/** 当前读者是否作者本人（管理入口显隐；后端仍逐请求鉴权，这里只是 UI 降噪） */
const isOwner = computed(() =>
  article.value && store.user && store.user.id === article.value.userId)

async function load() {
  loading.value = true
  error.value = ''
  try {
    article.value = await fetchArticle(route.params.id)
  } catch (e) {
    // 2001：不存在或无权见（后端"不可见即不存在"防枚举口径，前端不区分提示）
    error.value = e.message || '加载失败'
    article.value = null
  } finally {
    loading.value = false
  }
}

/** 作者治理动作：发布⇄下线（走状态机接口，成功后原地刷新，publishedAt 等派生字段以服务端为准） */
async function transit(status) {
  await changeArticleStatus(article.value.id, status)
  ElMessage.success(status === 1 ? '已发布' : '状态已更新')
  load()
}

async function remove() {
  await ElMessageBox.confirm('删除后可由管理员在回收站找回，确定？', '删除文章', { type: 'warning' })
  await deleteArticle(article.value.id)
  ElMessage.success('已删除')
  router.push('/user/articles')
}

function scrollTo(id) {
  document.getElementById(id)?.scrollIntoView({ behavior: 'smooth', block: 'start' })
}

onMounted(load)
watch(() => route.params.id, load)
</script>

<template>
  <main class="detail-page" v-loading="loading">
    <el-alert v-if="error" type="warning" :closable="false" :title="error"
              description="文章不存在、已删除，或尚未发布（草稿仅作者可见）。" />
    <template v-else-if="article">
      <header class="detail-head">
        <h1>{{ article.title }}</h1>
        <div class="meta">
          <!-- M10：署名链接到作者主页（/u/:id，公开页；打通"读一篇文章→认识这个作者"动线） -->
          <router-link :to="`/u/${article.userId}`" class="author-link">{{ article.author?.nickname }}</router-link>
          <span v-if="article.publishedAt">· 发布于 {{ article.publishedAt.replace('T', ' ').slice(0, 16) }}</span>
          <span>· {{ article.viewCount ?? 0 }} 次浏览</span>
          <el-tag v-if="article.status !== 1" type="warning" size="small">
            {{ article.status === 0 ? '草稿' : '已下线' }}
          </el-tag>
        </div>
        <p class="summary">
          {{ article.summary }}
          <el-tag v-if="article.summarySource === 1" size="small" type="primary" effect="plain">AI 生成</el-tag>
        </p>
        <div class="tags">
          <el-tag v-for="t in article.tags" :key="t.id" size="small" :color="t.color" effect="light">{{ t.name }}</el-tag>
        </div>
        <!-- 作者管理条：状态流转 / 编辑 / 删除（入口显隐是体验，真防线在服务端属主三防） -->
        <div v-if="isOwner" class="owner-bar">
          <el-button v-if="article.status !== 1" type="success" size="small" @click="transit(1)">发布</el-button>
          <el-button v-if="article.status === 1" type="warning" size="small" @click="transit(2)">下线</el-button>
          <el-button size="small" @click="router.push(`/user/articles/${article.id}/edit`)">编辑</el-button>
          <el-button size="small" type="danger" @click="remove">删除</el-button>
        </div>
      </header>

      <div class="detail-body">
        <article class="prose" v-html="rendered.html"></article>
        <nav v-if="rendered.toc.length" class="toc">
          <h4>目录</h4>
          <a v-for="t in rendered.toc" :key="t.id" :class="['toc-' + t.level]" @click.prevent="scrollTo(t.id)">
            {{ t.text }}
          </a>
        </nav>
      </div>

      <!-- M5：评论区（两级嵌套 + 发表/删除）；allowComment 闸门与审核态由服务端裁决 -->
      <CommentSection :article="article" />
    </template>
  </main>
</template>

<style scoped>
.detail-page { max-width: 960px; margin: 32px auto; padding: 0 16px; }
.meta { color: #909399; font-size: 14px; margin: 8px 0; display: flex; gap: 6px; align-items: center; flex-wrap: wrap; }
/* M10：作者昵称链（默认 a 样式带下划线蓝，视觉上与 meta 灰字打架，收敛为"继承色+hover 变蓝"） */
.author-link { color: inherit; text-decoration: none; }
.author-link:hover { color: #409eff; text-decoration: underline; }
.summary { color: #606266; background: #f5f7fa; padding: 10px 14px; border-left: 3px solid #409eff; }
.tags { display: flex; gap: 6px; margin: 10px 0; }
.owner-bar { margin: 12px 0; display: flex; gap: 8px; }
.detail-body { display: flex; gap: 24px; }
.prose { flex: 1; min-width: 0; line-height: 1.8; }
.prose :deep(img) { max-width: 100%; }
/* M15：pre 底色交给 highlight.js github 主题（.hljs 规则），这里只管版式与滚动；
   未接高亮的历史缓存 HTML（无 class="hljs" 的裸 pre）仍吃这条兜底底色 */
.prose :deep(pre) { padding: 12px; overflow: auto; border-radius: 6px; line-height: 1.6; }
.prose :deep(:not(pre) > code) { background: #f5f7fa; padding: 1px 5px; border-radius: 4px; font-size: .93em; }
.toc { width: 200px; flex-shrink: 0; position: sticky; top: 70px; align-self: flex-start; font-size: 13px; }
.toc a { display: block; color: #606266; text-decoration: none; padding: 3px 0; cursor: pointer; }
.toc a:hover { color: #409eff; }
.toc .toc-3 { padding-left: 14px; }
/* M15 移动端断点系统回归：详情页窄屏三件事——TOC 收起（200px 侧栏在手机上是
   挤压正文的负资产）、标题字号回落（960px 容器排版默认按桌面字号定的）、
   代码块保持横向滚动（pre 是少数"允许溢出"的元素，正文不许溢出）。 */
@media (max-width: 768px) {
  .detail-page { margin: 16px auto; }
  .detail-body { flex-direction: column; gap: 0; }
  .toc { display: none; }
  .detail-head h1 { font-size: 22px; }
  .prose :deep(pre) { font-size: 12.5px; }
}
</style>
