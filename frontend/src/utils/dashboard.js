/**
 * 仪表盘渲染纯函数（M8）—— AdminDashboardView 的数据整形层。
 *
 * 拆出来的动机与 M4 tagCloudFontSize 一致：CSS 条形图的"高度映射"是纯计算，
 * 放组件里就只能靠肉眼验收；提成纯函数后 Vitest 直接钉边界（全零序列、
 * 单点尖峰、负值脏数据）。图表不上 echarts 的决策沿用 M7 成本看板。
 */

/** 数值序列里取最大（条形高度归一的基准）；空序列/非数值回 0 */
export function maxOf(points) {
  if (!Array.isArray(points) || !points.length) return 0
  let m = 0
  for (const p of points) {
    const v = Number(p?.value)
    if (Number.isFinite(v) && v > m) m = v
  }
  return m
}

/**
 * 趋势点 → 条形高度百分比序列。
 * 最低可见高度 4%：全零/极小值日画成 0% 会让条形"消失"，视觉上误读为缺数据
 * （M7 看板同款处理）。max 为 0 时全序列回最低高度。
 */
export function barHeights(points, maxPx = 100) {
  const max = maxOf(points)
  return (Array.isArray(points) ? points : []).map((p) => {
    const v = Number(p?.value)
    const ratio = max > 0 && Number.isFinite(v) ? v / max : 0
    return { date: p?.date, value: Number.isFinite(v) ? v : 0,
      heightPct: Math.max(4, Math.round(ratio * maxPx)) }
  })
}

/**
 * 指标卡数据（键序即渲染序；标签集中在此，测试可断言"卡片不缺席"）。
 * aiCost7d 显示格式化：后端回传数值串/数字两态（BigDecimal 序列化口径），
 * Number 归一后保留 4 位小数原样展示——成本是答辩量化数据，不做四舍五入戏法。
 * totalPv 单独入参：它是 VO 顶层字段（口径=累计值），刻意不与 7 日 counts 混淆。
 */
export function dashboardCards(counts, totalPv) {
  const c = counts || {}
  const num = (v, d = 0) => (Number.isFinite(Number(v)) ? Number(v) : d)
  return [
    { key: 'articlePublished', label: '已发布文章', value: num(c.articlePublished) },
    { key: 'articleTotal', label: '文章总数(含草稿)', value: num(c.articleTotal) },
    { key: 'userTotal', label: '用户总数', value: num(c.userTotal) },
    { key: 'userActive', label: '正常用户', value: num(c.userActive) },
    { key: 'commentTotal', label: '评论总数', value: num(c.commentTotal) },
    { key: 'commentPending', label: '待审评论', value: num(c.commentPending), alert: num(c.commentPending) > 0 },
    { key: 'aiCalls7d', label: '近7日AI调用', value: num(c.aiCalls7d) },
    { key: 'aiCost7d', label: '近7日AI成本(元)', value: num(c.aiCost7d).toFixed(4) },
    { key: 'totalPv', label: '累计浏览', value: num(totalPv) }
  ]
}

/** MM-DD 短标签：折线横轴日期太长会挤成一团，年份在窗口内恒定无信息量 */
export function shortDate(isoDate) {
  const s = String(isoDate || '')
  return s.length >= 10 ? s.slice(5) : s
}

/**
 * 缓存健康度键族 → 展示行（M9）。
 * 后端 stats 是 {snake_or_camel: count} 的开放 Map，前端在这里把"认识的键"
 * 翻译成中文标签、"不认识的键"原样带上——后端演进（新增键族）时页面不缺席
 * 新指标，也不因字段集变化而崩。补偿队列深度 >0 亮 alert：那是"失效 DEL 失败
 * 待重试"的信号，非零说明 Redis 抖过，管理员应该知道。
 */
export function cacheStatsRows(stats) {
  const s = stats || {}
  const labels = {
    detailKeys: '详情缓存键',
    listKeys: '列表缓存键',
    listIndexMembers: '列表键登记数',
    pvKeys: '待回写PV键',
    pvDayKeys: '按日PV键',
    evictRetryPending: '失效补偿待处理'
  }
  const num = (v) => (Number.isFinite(Number(v)) ? Number(v) : 0)
  return Object.keys(labels).map((k) => ({
    key: k,
    label: labels[k],
    value: num(s[k]),
    alert: k === 'evictRetryPending' && num(s[k]) > 0
  }))
}
