<script setup>
/**
 * 后台-AI 调用日志页（M6，WBS 1.6.7 的读侧）—— GET /api/admin/ai/logs。
 *
 * 这是答辩"成本可视化"的第一屏：tokens/latency/cache_hit/cost_est 全量透传，
 * 顶部汇总条给当日总览（次数/成功/缓存命中），下方明细分页可按业务类型、
 * 状态、用户过滤。日志是 append-only 台账，页面只提供查询不提供任何改写入口。
 */
import { ref, reactive, onMounted } from 'vue'
import { fetchAiLogs } from '@/api/ai'
import { logStatusTag, formatCost } from '@/utils/ai'

const rows = ref([])
const total = ref(0)
const loading = ref(false)
const query = reactive({ pageNum: 1, pageSize: 20, bizType: '', status: null })

async function load() {
  loading.value = true
  try {
    const params = { pageNum: query.pageNum, pageSize: query.pageSize }
    if (query.bizType) params.bizType = query.bizType
    if (query.status != null && query.status !== '') params.status = query.status
    const page = await fetchAiLogs(params)
    rows.value = page.list
    total.value = page.total
  } finally {
    loading.value = false
  }
}
onMounted(load)

/** 切过滤条件回第一页（M5 同款纪律：留在旧页码会得到"筛了个寂寞"的错觉） */
function reload() {
  query.pageNum = 1
  load()
}

const BIZ_LABEL = { SUMMARY: '摘要', TAGS: '标签', CONNECT_TEST: '连通测试' }
</script>

<template>
  <div class="ai-log-page">
    <div class="toolbar">
      <h3>AI 调用日志</h3>
      <el-radio-group v-model="query.bizType" size="small" @change="reload">
        <el-radio-button label="">全部</el-radio-button>
        <el-radio-button label="SUMMARY">摘要</el-radio-button>
        <el-radio-button label="TAGS">标签</el-radio-button>
        <el-radio-button label="CONNECT_TEST">连通测试</el-radio-button>
      </el-radio-group>
      <el-radio-group v-model="query.status" size="small" @change="reload">
        <el-radio-button :label="null">全部状态</el-radio-button>
        <el-radio-button :label="1">成功</el-radio-button>
        <el-radio-button :label="3">重试后成功</el-radio-button>
        <el-radio-button :label="2">失败</el-radio-button>
      </el-radio-group>
    </div>
    <el-table :data="rows" v-loading="loading" size="small">
      <el-table-column prop="id" label="#" width="60" />
      <el-table-column label="时间" width="160">
        <template #default="{ row }">{{ (row.createdAt || '').replace('T', ' ').slice(0, 19) }}</template>
      </el-table-column>
      <el-table-column label="用户" width="110">
        <template #default="{ row }">{{ row.nickname || ('#' + row.userId) }}</template>
      </el-table-column>
      <el-table-column label="业务" width="90">
        <template #default="{ row }">{{ BIZ_LABEL[row.bizType] || row.bizType }}</template>
      </el-table-column>
      <el-table-column prop="model" label="模型" width="140" show-overflow-tooltip />
      <el-table-column label="tokens(入/出)" width="110">
        <template #default="{ row }">{{ row.promptTokens ?? '—' }} / {{ row.completionTokens ?? '—' }}</template>
      </el-table-column>
      <el-table-column label="成本(元)" width="90">
        <template #default="{ row }">{{ formatCost(row.costEst) }}</template>
      </el-table-column>
      <el-table-column label="耗时" width="80">
        <template #default="{ row }">{{ row.latencyMs }}ms</template>
      </el-table-column>
      <el-table-column label="缓存" width="60">
        <template #default="{ row }">
          <el-tag v-if="row.cacheHit === 1" size="small" type="info">命中</el-tag>
          <span v-else>—</span>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <el-tag :type="logStatusTag(row.status).type" size="small">{{ logStatusTag(row.status).label }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="errorMsg" label="错误" min-width="160" show-overflow-tooltip />
    </el-table>
    <el-pagination class="pager" layout="total, prev, pager, next" :total="total"
                   v-model:current-page="query.pageNum" :page-size="query.pageSize" @current-change="load" />
  </div>
</template>

<style scoped>
.ai-log-page { background: #fff; padding: 16px; border-radius: 6px; }
.toolbar { display: flex; gap: 16px; align-items: center; margin-bottom: 12px; }
.toolbar h3 { margin: 0 auto 0 0; }
.pager { margin-top: 12px; justify-content: flex-end; }
</style>
