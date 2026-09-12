<script setup>
/**
 * 我的评论页（M5）—— UC-U-12：平铺倒序、含全部状态（待审/被屏蔽对自己透明，
 * 状态标签就是"为什么别人看不到我这条"的答案，不做屏蔽理由文案）。
 * 数据来源 GET /api/comments?mine=1（后端只认 JWT 里的本人 id，无越权面）。
 */
import { ref, reactive, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { fetchMyComments, deleteComment } from '@/api/comment'
import { COMMENT_STATUS_LABELS, COMMENT_STATUS_TAG, relativeTime } from '@/utils/comment'

const router = useRouter()
const rows = ref([])
const loading = ref(false)
const pager = reactive({ pageNum: 1, pageSize: 20, total: 0 })

async function load() {
  loading.value = true
  try {
    const page = await fetchMyComments({ pageNum: pager.pageNum, pageSize: pager.pageSize })
    rows.value = page.list
    pager.total = page.total
  } finally {
    loading.value = false
  }
}
onMounted(load)

async function remove(row) {
  try {
    await ElMessageBox.confirm('删除后不可恢复，确定？', '删除评论', { type: 'warning' })
  } catch {
    return
  }
  await deleteComment(row.id)
  ElMessage.success('已删除')
  load()
}
</script>

<template>
  <div>
    <h2>我的评论</h2>
    <el-table v-loading="loading" :data="rows">
      <el-table-column label="内容" min-width="240">
        <template #default="{ row }">
          <div class="content-cell">{{ row.content }}</div>
        </template>
      </el-table-column>
      <el-table-column label="所在文章" min-width="160">
        <template #default="{ row }">
          <a class="art-link" @click="router.push(`/articles/${row.articleId}`)">
            {{ row.articleTitle ?? '(文章已删)' }}
          </a>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="90" align="center">
        <template #default="{ row }">
          <el-tag :type="COMMENT_STATUS_TAG[row.status]" size="small">{{ COMMENT_STATUS_LABELS[row.status] }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="时间" width="110">
        <template #default="{ row }">
          <span :title="row.createdAt">{{ relativeTime(row.createdAt) }}</span>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="80" align="center">
        <template #default="{ row }">
          <el-button link size="small" type="danger" @click="remove(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>
    <el-pagination class="pager" layout="total, prev, pager, next" :total="pager.total"
                   :page-size="pager.pageSize" :current-page="pager.pageNum"
                   @current-change="(p) => { pager.pageNum = p; load() }" />
  </div>
</template>

<style scoped>
h2 { margin: 0 0 14px; font-size: 18px; }
.content-cell { line-height: 1.6; word-break: break-word; }
.art-link { color: #409eff; cursor: pointer; }
.pager { margin-top: 12px; justify-content: flex-end; }
</style>
