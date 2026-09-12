<script setup>
/**
 * 后台-仪表盘（M8 落地版，替换 M2 占位）—— GET /api/admin/dashboard。
 * UC-A-10 标 P2，但 API 文档把接口列进 §8，M8 顺手做轻量版：
 * 指标卡 + CSS 条形趋势（决策与 M7 成本看板一致——两页共用一套条形语言，
 * 不为两条趋势线引入 echarts 的 300KB 分包）。
 *
 * PV 趋势本期是后端声明的占位零序列（数据源在 M9 计数回写体系），页面用
 * el-alert 把 note 直出——"图上有一条全零线却不解释"是会被答辩评委抓住的点。
 */
import { ref, computed, onMounted } from 'vue'
import { fetchDashboard } from '@/api/system'
import { dashboardCards, barHeights, shortDate } from '@/utils/dashboard'

const data = ref(null)
const loading = ref(false)

async function load() {
  loading.value = true
  try {
    data.value = await fetchDashboard()
  } finally {
    loading.value = false
  }
}

const cards = computed(() =>
  data.value ? dashboardCards(data.value.counts, data.value.totalPv) : [])
const aiBars = computed(() =>
  data.value ? barHeights(data.value.aiTrend) : [])

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

    <section class="block">
      <h3>近 7 日 AI 调用</h3>
      <div class="bars">
        <div v-for="b in aiBars" :key="b.date" class="bar-col" :title="`${shortDate(b.date)}: ${b.value} 次`">
          <div class="bar" :style="{ height: b.heightPct + '%' }"></div>
          <div class="bar-x">{{ shortDate(b.date) }}</div>
        </div>
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
.block { background: #fff; border: 1px solid #ebeef5; border-radius: 8px; padding: 14px 16px; }
.block h3 { margin: 0 0 10px; font-size: 14px; }
.bars { display: flex; align-items: flex-end; gap: 10px; height: 140px; }
.bar-col { flex: 1; display: flex; flex-direction: column; justify-content: flex-end; align-items: center; height: 100%; }
.bar { width: 60%; min-width: 18px; background: #409eff; border-radius: 3px 3px 0 0; }
.bar-x { font-size: 11px; color: #909399; margin-top: 4px; }
</style>
