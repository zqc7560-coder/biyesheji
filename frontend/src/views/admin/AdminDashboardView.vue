<script setup>
/**
 * 后台-仪表盘（M8 落地，M9 补全 PV 趋势与缓存运维区）。
 * UC-A-10 标 P2，但 API 文档把接口列进 §8，M8 做轻量版：
 * 指标卡 + CSS 条形趋势（决策与 M7 成本看板一致——两页共用一套条形语言，
 * 不为趋势线引入 echarts 的 300KB 分包）。
 *
 * M9 变化：
 * 1) PV 趋势从"占位零序列"换成 blog:pvday 真实数据（后端口径见 DashboardService），
 *    与 AI 趋势并排同布局——两条线共用 barHeights 的高度映射，视觉语言一致；
 * 2) 新增"缓存运维"块：健康度键族计数 + 一键清展示缓存（R-04 应急口的入口）。
 *    清缓存是低频运维动作，不值得独立菜单页，挂在它最常被想起的地方（仪表盘）。
 * 后端 note 字段仍用 el-alert 直出：数据口径透明化是答辩可讲点，不藏。
 */
import { ref, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { fetchDashboard, fetchCacheStats, clearDisplayCache, triggerPvSync } from '@/api/system'
import { dashboardCards, barHeights, shortDate, cacheStatsRows } from '@/utils/dashboard'

const data = ref(null)
const cacheStats = ref(null)
const loading = ref(false)
const clearing = ref(false)
const syncing = ref(false)

async function load() {
  loading.value = true
  try {
    data.value = await fetchDashboard()
    // 缓存统计并行取失败不拖垮仪表盘主数据（运维区缺席好过整页报错）
    try {
      cacheStats.value = await fetchCacheStats()
    } catch {
      cacheStats.value = null
    }
  } finally {
    loading.value = false
  }
}

const cards = computed(() =>
  data.value ? dashboardCards(data.value.counts, data.value.totalPv) : [])
const aiBars = computed(() =>
  data.value ? barHeights(data.value.aiTrend) : [])
const pvBars = computed(() =>
  data.value ? barHeights(data.value.pvTrend) : [])
const cacheRows = computed(() => cacheStatsRows(cacheStats.value))

/** 一键清缓存：二次确认（清除引发一波回源风暴虽无害，但要让操作者知情） */
async function onClearCache() {
  try {
    await ElMessageBox.confirm(
      '将清空文章详情/列表展示缓存（PV 计数、AI 结果缓存、Token 黑名单不受影响）。' +
      '清空后首批访问会回源查库，属正常现象。',
      '清空展示缓存', { type: 'warning', confirmButtonText: '清空', cancelButtonText: '取消' })
  } catch {
    return // 取消
  }
  clearing.value = true
  try {
    const res = await clearDisplayCache()
    ElMessage.success(`已清理 ${res.deleted ?? 0} 个缓存键`)
    cacheStats.value = await fetchCacheStats()
  } finally {
    clearing.value = false
  }
}

/**
 * 手动触发一轮 PV 回写（M9）：5min 定时节拍对"立刻要看到账"的现场太慢，
 * 本口幂等（取走即归零），重复点击不会双计——无需二次确认。
 * 回写后 totalPv/趋势会变化，顺带刷新主数据与统计。
 */
async function onSyncPv() {
  syncing.value = true
  try {
    const res = await triggerPvSync()
    ElMessage.success(`已落库 ${res.applied ?? 0} 篇（+${res.totalDelta ?? 0} 浏览）`)
    data.value = await fetchDashboard()
    cacheStats.value = await fetchCacheStats()
  } finally {
    syncing.value = false
  }
}

onMounted(load)
</script>

<template>
  <div class="dash" v-loading="loading">
    <div class="head">
      <h2>仪表盘</h2>
      <el-button size="small" @click="load">刷新</el-button>
    </div>

    <div class="cards">
      <div v-for="c in cards" :key="c.key" class="card" :class="{ alert: c.alert }">
        <div class="card-v">{{ c.value }}</div>
        <div class="card-l">{{ c.label }}</div>
      </div>
    </div>

    <div class="trends">
      <section class="block">
        <h3>近 7 日 AI 调用</h3>
        <div class="bars">
          <div v-for="b in aiBars" :key="b.date" class="bar-col" :title="`${shortDate(b.date)}: ${b.value} 次`">
            <div class="bar" :style="{ height: b.heightPct + '%' }"></div>
            <div class="bar-x">{{ shortDate(b.date) }}</div>
          </div>
        </div>
      </section>

      <section class="block">
        <h3>近 7 日浏览量（M9 实时）</h3>
        <div class="bars">
          <div v-for="b in pvBars" :key="b.date" class="bar-col" :title="`${shortDate(b.date)}: ${b.value} 次浏览`">
            <div class="bar bar-pv" :style="{ height: b.heightPct + '%' }"></div>
            <div class="bar-x">{{ shortDate(b.date) }}</div>
          </div>
        </div>
      </section>
    </div>

    <section class="block cache">
      <div class="cache-head">
        <h3>缓存运维</h3>
        <div class="cache-actions">
          <el-button size="small" plain :loading="syncing" @click="onSyncPv">
            立即回写浏览量
          </el-button>
          <el-button size="small" type="danger" plain :loading="clearing" @click="onClearCache">
            一键清展示缓存
          </el-button>
        </div>
      </div>
      <div class="cache-grid">
        <span v-for="row in cacheRows" :key="row.key" class="cache-item" :class="{ alert: row.alert }">
          {{ row.label }}: <b>{{ row.value }}</b>
        </span>
      </div>
    </section>

    <el-alert v-if="data?.note" type="info" :closable="false" :title="data.note"
              style="margin-top:14px" />
  </div>
</template>

<style scoped>
.dash { max-width: 980px; }
.head { display: flex; justify-content: space-between; align-items: center; }
.cards { display: grid; grid-template-columns: repeat(auto-fill, minmax(150px, 1fr)); gap: 12px; margin: 14px 0; }
.card { background: #fff; border: 1px solid #ebeef5; border-radius: 8px; padding: 14px 16px; }
.card.alert { border-color: #e6a23c; background: #fdf6ec; }
.card-v { font-size: 22px; font-weight: 600; }
.card-l { color: #909399; font-size: 12px; margin-top: 4px; }
.trends { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; }
@media (max-width: 900px) { .trends { grid-template-columns: 1fr; } }
.block { background: #fff; border: 1px solid #ebeef5; border-radius: 8px; padding: 14px 16px; }
.block h3 { margin: 0 0 10px; font-size: 14px; }
.bars { display: flex; align-items: flex-end; gap: 10px; height: 140px; }
.bar-col { flex: 1; display: flex; flex-direction: column; justify-content: flex-end; align-items: center; height: 100%; }
.bar { width: 60%; min-width: 18px; background: #409eff; border-radius: 3px 3px 0 0; }
.bar-pv { background: #67c23a; } /* PV 用绿系与 AI 蓝区分，同一屏两条线不混淆 */
.bar-x { font-size: 11px; color: #909399; margin-top: 4px; }
.cache { margin-top: 12px; }
.cache-head { display: flex; justify-content: space-between; align-items: center; }
.cache-head h3 { margin: 0; }
.cache-actions { display: flex; gap: 8px; }
.cache-grid { display: flex; flex-wrap: wrap; gap: 8px 22px; margin-top: 10px; font-size: 13px; color: #606266; }
.cache-item.alert { color: #e6a23c; font-weight: 600; }
</style>
