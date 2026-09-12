<script setup>
/**
 * 后台-AI 成本统计页（M7，课题重点收尾）—— GET /api/admin/ai/stats。
 *
 * 答辩演示"AI 成本可视化"的主屏：顶部四张指标卡（今日成本/窗口命中率/总调用/日均），
 * 中部按日与按业务两张明细表，右侧用户成本 Top10；页尾挂"批量补全"治理按钮
 * （POST /api/ai/batch）——批量是唯一会替文章写库的 AI 入口，把它放在成本页旁边
 * 是刻意的：管理员点它之前先看到花了多少钱，动线与心智对齐。
 *
 * 图表刻意不上 echarts（依赖红线：不为演示效果加 800KB 包）——表格 + 简单条形
 * 用 CSS 宽度百分比就能把"按日趋势"讲清楚，毕设评审看的是口径不是炫技。
 */
import { ref, reactive, onMounted, computed } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { fetchAiStats, aiBatch } from '@/api/ai'
import { statsWindow, formatHitRate, formatCost } from '@/utils/ai'

const loading = ref(false)
const stats = ref(null)
const preset = ref('30d')

async function load() {
  loading.value = true
  try {
    stats.value = await fetchAiStats(statsWindow(preset.value))
  } catch {
    stats.value = null
  } finally {
    loading.value = false
  }
}
onMounted(load)

/** 按日成本的最大值：给"成本条形"当 100% 基准（全零时回 1 防除零宽度 NaN） */
const maxDayCost = computed(() => {
  const rows = stats.value?.byDay || []
  return Math.max(1e-9, ...rows.map((r) => Number(r.cost) || 0))
})

const BIZ_LABEL = { SUMMARY: '摘要', TAGS: '标签', CONNECT_TEST: '连通测试' }

// ---------- 批量补全治理按钮 ----------
const batchBusy = ref(false)
async function runBatch() {
  // 二次确认：批量=自动写 20 篇文章的摘要/标签，误触成本真实存在（花钱+改库），
  // 一个 confirm 对话框是最便宜的护栏
  try {
    await ElMessageBox.confirm(
      '将自动为最多 20 篇「已发布且缺摘要或缺标签」的文章生成并写入 AI 摘要/标签（消耗真实调用配额）。继续？',
      'AI 批量补全', { type: 'warning', confirmButtonText: '开始补全', cancelButtonText: '取消' })
  } catch { return }
  batchBusy.value = true
  try {
    const r = await aiBatch()
    ElMessage.success(r.message || `已受理 ${r.accepted} 篇`)
  } catch (e) {
    // 拦截器已 toast 信封错误；这里只处理"受理但 0 篇"不算错的分支之外的情形
  } finally {
    batchBusy.value = false
    load()   // 补全会产生新日志，统计顺手刷新
  }
}
</script>

<template>
  <div class="ai-stats-page" v-loading="loading">
    <div class="toolbar">
      <h3>AI 成本统计</h3>
      <el-radio-group v-model="preset" size="small" @change="load">
        <el-radio-button label="7d">近 7 天</el-radio-button>
        <el-radio-button label="30d">近 30 天</el-radio-button>
        <el-radio-button label="90d">近 90 天</el-radio-button>
      </el-radio-group>
      <el-button type="primary" plain size="small" :loading="batchBusy" @click="runBatch">
        🪄 批量补全缺摘要/标签的文章
      </el-button>
    </div>

    <el-row :gutter="12" class="cards" v-if="stats">
      <el-col :span="6"><el-card shadow="never"><div class="k">今日成本（元）</div><div class="v">{{ formatCost(stats.todayCost) }}</div></el-card></el-col>
      <el-col :span="6"><el-card shadow="never"><div class="k">窗口缓存命中率</div><div class="v">{{ formatHitRate(stats.cacheHitRate) }}</div><div class="sub">目标 ≥ 30%</div></el-card></el-col>
      <el-col :span="6"><el-card shadow="never"><div class="k">窗口总调用</div><div class="v">{{ stats.totalCalls }}</div></el-card></el-col>
      <el-col :span="6"><el-card shadow="never"><div class="k">统计窗口</div><div class="v small">{{ stats.dateFrom }} ~ {{ stats.dateTo }}</div></el-card></el-col>
    </el-row>

    <el-row :gutter="12" class="tables">
      <el-col :span="14">
        <h4>按日明细</h4>
        <el-table :data="stats?.byDay || []" size="small" max-height="360">
          <el-table-column prop="date" label="日期" width="110" />
          <el-table-column prop="calls" label="调用" width="60" />
          <el-table-column prop="success" label="成功" width="60" />
          <el-table-column prop="failures" label="失败" width="60" />
          <el-table-column prop="cacheHits" label="命中" width="60" />
          <el-table-column label="成本(元)">
            <template #default="{ row }">
              <div class="bar-wrap">
                <div class="bar" :style="{ width: (Number(row.cost) / maxDayCost * 100) + '%' }"></div>
                <span class="bar-txt">{{ formatCost(row.cost) }}</span>
              </div>
            </template>
          </el-table-column>
        </el-table>
      </el-col>
      <el-col :span="10">
        <h4>按业务类型</h4>
        <el-table :data="Object.entries(stats?.byBiz || {}).map(([k, v]) => ({ biz: k, ...v }))" size="small">
          <el-table-column label="业务" width="100">
            <template #default="{ row }">{{ BIZ_LABEL[row.biz] || row.biz }}</template>
          </el-table-column>
          <el-table-column prop="calls" label="次数" width="60" />
          <el-table-column prop="cacheHits" label="命中" width="60" />
          <el-table-column label="成本(元)" width="100">
            <template #default="{ row }">{{ formatCost(row.cost) }}</template>
          </el-table-column>
          <el-table-column label="均耗时">
            <template #default="{ row }">{{ row.avgLatencyMs }}ms</template>
          </el-table-column>
        </el-table>
        <h4 class="mt">用户成本 Top10</h4>
        <el-table :data="stats?.topUsers || []" size="small" max-height="220">
          <el-table-column prop="nickname" label="用户" />
          <el-table-column prop="calls" label="次数" width="70" />
          <el-table-column label="成本(元)" width="100">
            <template #default="{ row }">{{ formatCost(row.cost) }}</template>
          </el-table-column>
        </el-table>
      </el-col>
    </el-row>
  </div>
</template>

<style scoped>
.ai-stats-page { background: #fff; padding: 16px; border-radius: 6px; }
.toolbar { display: flex; gap: 16px; align-items: center; margin-bottom: 12px; }
.toolbar h3 { margin: 0 auto 0 0; }
.cards { margin-bottom: 16px; }
.cards .k { color: #909399; font-size: 12px; }
.cards .v { font-size: 22px; font-weight: 600; margin-top: 4px; }
.cards .v.small { font-size: 14px; }
.cards .sub { color: #67c23a; font-size: 11px; }
.tables h4 { margin: 8px 0; }
.tables .mt { margin-top: 18px; }
.bar-wrap { position: relative; min-width: 120px; }
.bar { position: absolute; left: 0; top: 0; bottom: 0; background: #ecf5ff; border-radius: 3px; }
.bar-txt { position: relative; padding-left: 4px; }
</style>
