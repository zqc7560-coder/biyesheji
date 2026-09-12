<script setup>
/**
 * 后台 · 文章管理（M3）—— UC-A-02：跨用户检索全站文章，强制下线/置顶。
 * 数据口是同一 GET /api/articles（ADMIN 的 status/authorId 参数才生效——
 * 服务端"越权三防"保证非 ADMIN 传这些参数也只会拿到公开视图）。
 * 治理动作：强制下线（PATCH /{id}/offline）、置顶开关（PATCH /{id}/top）。
 * "删除/回收站彻底删除"入口本期只挂强制下线（删除是作者权限语义，
 * 管理员回收站需要 deleted=1 的查询通道，属 M4 内容治理扩展——台账已注记）。
 */
import { ref, reactive, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { listArticles, forceOffline, setTop } from '@/api/article'

const data = reactive({ list: [], total: 0 })
const query = reactive({ status: null, keyword: '', pageNum: 1, pageSize: 15 })
const loading = ref(false)

const STATUS_TAG = { 0: { text: '草稿', type: 'info' }, 1: { text: '已发布', type: 'success' }, 2: { text: '已下线', type: 'warning' } }

async function fetchList(page = 1) {
  query.pageNum = page
  loading.value = true
  try {
    const res = await listArticles({
      status: query.status ?? undefined, keyword: query.keyword || undefined,
      pageNum: page, pageSize: query.pageSize
    })
    Object.assign(data, { list: res.list, total: res.total })
  } finally {
    loading.value = false
  }
}

async function offline(row) {
  await forceOffline(row.id)
  ElMessage.success('已强制下线')
  fetchList(query.pageNum)
}

/**
 * 置顶开关：列表接口按 top_flag 恒先排序，翻转后原地刷新即见位次变化（演示效果直观）。
 * VO 契约不含 topFlag 字段（后端 ArticleVO 无此列，列表以"排最前"体现），
 * 因此置顶/取消是两个显式动作按钮，而不是一个依赖回显状态的开关。
 */
async function toggleTop(row, flag) {
  await setTop(row.id, flag)
  ElMessage.success(flag === 1 ? '已置顶' : '已取消置顶')
  fetchList(query.pageNum)
}

onMounted(() => fetchList(1))
</script>

<template>
  <div>
    <div class="bar">
      <el-input v-model="query.keyword" placeholder="标题/正文关键词" clearable style="width: 240px"
                @keyup.enter="fetchList(1)" @clear="fetchList(1)" />
      <el-select v-model="query.status" placeholder="状态" clearable style="width: 120px" @change="fetchList(1)">
        <el-option label="草稿" :value="0" /><el-option label="已发布" :value="1" /><el-option label="已下线" :value="2" />
      </el-select>
      <el-button type="primary" @click="fetchList(1)">查询</el-button>
    </div>

    <el-table v-loading="loading" :data="data.list" border>
      <el-table-column prop="id" label="ID" width="70" />
      <el-table-column prop="title" label="标题" min-width="220" show-overflow-tooltip />
      <el-table-column label="作者" width="120"><template #default="{ row }">{{ row.author?.nickname }}</template></el-table-column>
      <el-table-column label="分类" width="100"><template #default="{ row }">{{ row.category?.name || '—' }}</template></el-table-column>
      <el-table-column label="状态" width="90">
        <template #default="{ row }"><el-tag :type="STATUS_TAG[row.status].type" size="small">{{ STATUS_TAG[row.status].text }}</el-tag></template>
      </el-table-column>
      <el-table-column label="浏览" width="80"><template #default="{ row }">{{ row.viewCount ?? 0 }}</template></el-table-column>
      <el-table-column label="发布时间" width="150">
        <template #default="{ row }">{{ String(row.publishedAt || '—').replace('T', ' ').slice(0, 16) }}</template>
      </el-table-column>
      <el-table-column label="治理动作" width="200" fixed="right">
        <template #default="{ row }">
          <el-button v-if="row.status === 1" size="small" type="warning" @click="offline(row)">强制下线</el-button>
          <el-button size="small" @click="toggleTop(row, 1)">置顶</el-button>
          <el-button size="small" @click="toggleTop(row, 0)">取消置顶</el-button>
          <el-button size="small" @click="$router.push(`/articles/${row.id}`)">预览</el-button>
        </template>
      </el-table-column>
    </el-table>
    <el-pagination layout="prev, pager, next" :total="data.total" :page-size="query.pageSize"
                   :current-page="query.pageNum" @current-change="fetchList" class="pager" />
  </div>
</template>

<style scoped>
.bar { display: flex; gap: 10px; margin-bottom: 14px; }
.pager { margin-top: 14px; justify-content: flex-end; }
</style>
