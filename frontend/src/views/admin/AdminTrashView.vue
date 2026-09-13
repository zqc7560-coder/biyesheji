<script setup>
/**
 * 后台 · 回收站（M10）—— UC-U-09「管理员可在回收站彻底删除」的治理页，
 * 台账 M3 顺延项「管理员回收站/彻底删除通道随 M10 后台打通」在此闭环。
 *
 * 动作语义（与后端契约一一对应，护栏都在服务端，这里只做入口与确认）：
 * · 恢复：POST /api/articles/{id}/restore —— 只翻 deleted 位，status 保持删除前
 *   原值（草稿恢复回草稿、发布恢复回发布），所以列表要展示删除前状态；
 * · 彻底删除：DELETE /api/articles/{id}/purge —— 物理删行不可逆，用 ElMessageBox
 *   输入式二次确认都不为过，这里取"强文案 confirm"折中（毕设演示节奏不宜过繁）。
 */
import { ref, reactive, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { listTrash, restoreArticle, purgeArticle } from '@/api/article'

const data = reactive({ list: [], total: 0 })
const query = reactive({ keyword: '', pageNum: 1, pageSize: 15 })
const loading = ref(false)

const STATUS_TAG = { 0: { text: '草稿', type: 'info' }, 1: { text: '已发布', type: 'success' }, 2: { text: '已下线', type: 'warning' } }

async function fetchList(page = 1) {
  query.pageNum = page
  loading.value = true
  try {
    const res = await listTrash({ keyword: query.keyword || undefined, pageNum: page, pageSize: query.pageSize })
    Object.assign(data, { list: res.list, total: res.total })
  } finally {
    loading.value = false
  }
}

async function restore(row) {
  await restoreArticle(row.id)
  // 后端信封 message 是动作文案（"已恢复"/幂等的"文章已在库中"），但 request.js
  // 成功路径只回 data——拦截器已把 message 吞进信封层。前端文案本地化，行为以后端为准
  ElMessage.success('已恢复（状态保持删除前原值）')
  fetchList(query.pageNum)
}

async function purge(row) {
  try {
    await ElMessageBox.confirm(
      `《${row.title}》将被物理删除，文章与其评论不可恢复。确定？`,
      '彻底删除', { confirmButtonText: '不可逆删除', cancelButtonText: '取消', type: 'error' })
  } catch { return }   // 取消（ElMessageBox reject 约定）
  await purgeArticle(row.id)
  ElMessage.success('已彻底删除')
  fetchList(query.pageNum)
}

onMounted(() => fetchList(1))
</script>

<template>
  <div>
    <div class="bar">
      <el-input v-model="query.keyword" placeholder="按标题搜索回收站" clearable style="width: 240px"
                @keyup.enter="fetchList(1)" @clear="fetchList(1)" />
      <el-button type="primary" @click="fetchList(1)">查询</el-button>
      <span class="tip">恢复 = 回到文章管理列表且状态不变；彻底删除 = 物理删行（含连带评论）</span>
    </div>

    <el-table v-loading="loading" :data="data.list" border>
      <el-table-column prop="id" label="ID" width="70" />
      <el-table-column prop="title" label="标题" min-width="220" show-overflow-tooltip />
      <el-table-column label="作者" width="120"><template #default="{ row }">{{ row.author?.nickname || '—' }}</template></el-table-column>
      <el-table-column label="删除前状态" width="110">
        <template #default="{ row }">
          <el-tag :type="STATUS_TAG[row.status]?.type || 'info'" size="small">{{ STATUS_TAG[row.status]?.text || row.status }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="删除时间" width="150">
        <template #default="{ row }">{{ String(row.updatedAt || '').replace('T', ' ').slice(0, 16) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="180" fixed="right">
        <template #default="{ row }">
          <el-button size="small" type="success" @click="restore(row)">恢复</el-button>
          <el-button size="small" type="danger" @click="purge(row)">彻底删除</el-button>
        </template>
      </el-table-column>
    </el-table>
    <el-pagination layout="prev, pager, next" :total="data.total" :page-size="query.pageSize"
                   :current-page="query.pageNum" @current-change="fetchList" class="pager" />
  </div>
</template>

<style scoped>
.bar { display: flex; gap: 10px; margin-bottom: 14px; align-items: center; }
.tip { color: #909399; font-size: 12px; }
.pager { margin-top: 14px; justify-content: flex-end; }
</style>
