<script setup>
/**
 * 后台-评论审核页（M5）—— UC-A-05：全站评论列表、审核（通过/屏蔽）、删除违规。
 *
 * 队列默认聚焦"待审"（status=0）：管理员进来第一件事是清队列，不是逛全站。
 * 平铺而非嵌套展示是后端 adminPage 同款决策——审核以"条"为决策单位，
 * 树形会把"屏蔽这条回复"埋进折叠里（CommentService.adminPage 注释互引）。
 *
 * 通过/屏蔽都是"单向动作 + 幂等后端"：按钮点了不假删行，成功后 load()
 * 重拉——待审队列会整页位移，本地 splice 优化在这种交互里是错的。
 */
import { ref, reactive, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { adminListComments, auditComment, deleteComment } from '@/api/comment'
import { COMMENT_STATUS_LABELS, COMMENT_STATUS_TAG, relativeTime } from '@/utils/comment'

const rows = ref([])
const loading = ref(false)
const pager = reactive({ pageNum: 1, pageSize: 20, total: 0 })
/** 状态筛选：null=全部；0/1/2 对应队列/已通过/已屏蔽（与后端 ?status= 语义一致） */
const statusFilter = ref(0)

async function load() {
  loading.value = true
  try {
    const page = await adminListComments({
      pageNum: pager.pageNum,
      pageSize: pager.pageSize,
      ...(statusFilter.value === null ? {} : { status: statusFilter.value })
    })
    rows.value = page.list
    pager.total = page.total
  } finally {
    loading.value = false
  }
}
onMounted(load)

function switchStatus(s) {
  statusFilter.value = s
  pager.pageNum = 1 // 换队列必须回第一页，否则停在新筛选的越界页显示空表
  load()
}

async function doAudit(row, target) {
  await auditComment(row.id, target)
  ElMessage.success(target === 1 ? '已通过' : '已屏蔽（其下回复同步隐藏）')
  load()
}

async function doDelete(row) {
  try {
    await ElMessageBox.confirm(`删除评论「${row.content.slice(0, 30)}…」？其下回复会一并移除。`, '删除确认', { type: 'warning' })
  } catch {
    return
  }
  await deleteComment(row.id)
  ElMessage.success('已删除')
  load()
}

function jumpArticle(row) {
  window.open(`/articles/${row.articleId}`, '_blank')
}
</script>

<template>
  <div class="page">
    <div class="toolbar">
      <h2>评论审核</h2>
      <el-radio-group :model-value="statusFilter" @change="switchStatus">
        <el-radio-button :value="0">待审核</el-radio-button>
        <el-radio-button :value="1">已通过</el-radio-button>
        <el-radio-button :value="2">已屏蔽</el-radio-button>
        <el-radio-button :value="null">全部</el-radio-button>
      </el-radio-group>
    </div>

    <el-table v-loading="loading" :data="rows" style="width: 100%">
      <el-table-column label="内容" min-width="260">
        <!-- 文本插值渲染：后端已清洗，这里同样不给 v-html（XSS 双闸的前端半闸） -->
        <template #default="{ row }">
          <div class="content-cell">{{ row.content }}</div>
        </template>
      </el-table-column>
      <el-table-column label="发表者" width="120">
        <template #default="{ row }">{{ row.user?.nickname ?? '已注销' }}</template>
      </el-table-column>
      <el-table-column label="所在文章" min-width="160">
        <template #default="{ row }">
          <a class="art-link" @click="jumpArticle(row)">{{ row.articleTitle ?? '(文章已删)' }}</a>
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
      <el-table-column label="操作" width="180" align="center">
        <template #default="{ row }">
          <el-button v-if="row.status !== 1" link size="small" type="success" @click="doAudit(row, 1)">通过</el-button>
          <el-button v-if="row.status !== 2" link size="small" type="warning" @click="doAudit(row, 2)">屏蔽</el-button>
          <el-button link size="small" type="danger" @click="doDelete(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-pagination class="pager" layout="total, prev, pager, next" :total="pager.total"
                   :page-size="pager.pageSize" :current-page="pager.pageNum"
                   @current-change="(p) => { pager.pageNum = p; load() }" />
  </div>
</template>

<style scoped>
.page { padding: 4px; }
.toolbar { display: flex; justify-content: space-between; align-items: center; margin-bottom: 14px; flex-wrap: wrap; gap: 8px; }
.toolbar h2 { margin: 0; font-size: 18px; }
.content-cell { line-height: 1.6; word-break: break-word; }
.art-link { color: #409eff; cursor: pointer; }
.pager { margin-top: 12px; justify-content: flex-end; }
</style>
