<script setup>
/**
 * 我的文章（M3）—— UC-U-12"查看我的列表（按状态筛选）"。
 * 数据口：GET /api/articles?mine=1&status=（后端 mine 通道不限状态，草稿箱语义）。
 * 动作：编辑 / 发布 / 下线 / 删除 —— 全部复用 §4 文章接口，无前端特权逻辑。
 */
import { ref, reactive, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { listArticles, changeArticleStatus, deleteArticle } from '@/api/article'

const router = useRouter()
const data = reactive({ list: [], total: 0 })
const query = reactive({ status: null, pageNum: 1, pageSize: 10 })
const loading = ref(false)

const STATUS_TAG = { 0: { text: '草稿', type: 'info' }, 1: { text: '已发布', type: 'success' }, 2: { text: '已下线', type: 'warning' } }

async function fetchList(page = 1) {
  query.pageNum = page
  loading.value = true
  try {
    const res = await listArticles({ mine: 1, status: query.status ?? undefined, pageNum: page, pageSize: query.pageSize })
    Object.assign(data, { list: res.list, total: res.total })
  } finally {
    loading.value = false
  }
}

async function transit(row, status) {
  await changeArticleStatus(row.id, status)
  ElMessage.success(status === 1 ? '已发布' : status === 2 ? '已下线' : '已回到草稿')
  fetchList(query.pageNum)
}

async function remove(row) {
  await ElMessageBox.confirm(`删除《${row.title}》？`, '删除文章', { type: 'warning' })
  await deleteArticle(row.id)
  ElMessage.success('已删除')
  fetchList(query.pageNum)
}

onMounted(() => fetchList(1))
</script>

<template>
  <div class="mine">
    <div class="bar">
      <h2>我的文章</h2>
      <div class="ops">
        <el-radio-group v-model="query.status" @change="fetchList(1)">
          <el-radio-button :value="null">全部</el-radio-button>
          <el-radio-button :value="0">草稿</el-radio-button>
          <el-radio-button :value="1">已发布</el-radio-button>
          <el-radio-button :value="2">已下线</el-radio-button>
        </el-radio-group>
        <el-button type="primary" @click="router.push('/user/articles/new')">✍ 写文章</el-button>
      </div>
    </div>

    <el-table v-loading="loading" :data="data.list" border>
      <el-table-column prop="title" label="标题" min-width="220" show-overflow-tooltip />
      <el-table-column label="状态" width="90">
        <template #default="{ row }">
          <el-tag :type="STATUS_TAG[row.status].type" size="small">{{ STATUS_TAG[row.status].text }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="评论" width="70"><template #default="{ row }">{{ row.commentCount ?? 0 }}</template></el-table-column>
      <el-table-column label="浏览" width="80"><template #default="{ row }">{{ row.viewCount ?? 0 }}</template></el-table-column>
      <el-table-column label="更新时间" width="150">
        <template #default="{ row }">{{ String(row.updatedAt || '').replace('T', ' ').slice(0, 16) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="230" fixed="right">
        <template #default="{ row }">
          <el-button size="small" @click="router.push(`/user/articles/${row.id}/edit`)">编辑</el-button>
          <el-button v-if="row.status !== 1" size="small" type="success" @click="transit(row, 1)">发布</el-button>
          <el-button v-if="row.status === 1" size="small" type="warning" @click="transit(row, 2)">下线</el-button>
          <el-button size="small" type="danger" @click="remove(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>
    <el-pagination layout="prev, pager, next" :total="data.total" :page-size="query.pageSize"
                   :current-page="query.pageNum" @current-change="fetchList" class="pager" />
  </div>
</template>

<style scoped>
.mine { max-width: 1000px; margin: 24px auto; padding: 0 16px; }
.bar { display: flex; justify-content: space-between; align-items: center; margin-bottom: 14px; flex-wrap: wrap; gap: 10px; }
.ops { display: flex; gap: 12px; align-items: center; }
.pager { margin-top: 14px; justify-content: flex-end; }
</style>
