/**
 * 归档视图纯函数（M10）—— UC-V-06「按年/月归档浏览」的数据整形层。
 *
 * 后端 GET /api/articles/archive 返回 {"2026":{"09":[{id,title,publishedAt}]}}，
 * 嵌套顺序由 SQL 的 published_at DESC 决定（LinkedHashMap 保序），但前端渲染
 * 需要"年→月→篇"的显式列表结构 + 统计摘要。整形放纯函数而不是组件里：
 * ① 组件在 node 环境不可测（无 jsdom，环境约束依赖最小化）；② 排序/兜底
 * 逻辑（后端乱序、月份缺 0、非法日期）是归档页"看起来怪"的 bug 高发区。
 */

/** 月份补零："9" → "09"；已是两位则原样（后端约定两位，前端不信任契约——防御性归一） */
export function padMonth(m) {
  const n = Number(m)
  if (!Number.isFinite(n) || n < 1 || n > 12) return null   // 非法月份直接判死，渲染层跳过
  return String(n).padStart(2, '0')
}

/**
 * 归档树 → 渲染区块列表 [{year, total, months:[{month, items}]}]。
 * 年、月都显式降序重排（不依赖服务端 map 迭代顺序——JSON 对象键序在极端
 * 序列化实现下不保证稳定，排序成本微秒级，换来的是"页面次序永不跳变"）。
 */
export function groupArchive(tree) {
  if (!tree || typeof tree !== 'object') return []
  const years = Object.keys(tree)
    .filter((y) => /^\d{4}$/.test(y))
    .sort((a, b) => Number(b) - Number(a))
  const out = []
  for (const y of years) {
    const monthMap = tree[y] || {}
    const months = Object.keys(monthMap)
      .map(padMonth)
      .filter(Boolean)
      .sort((a, b) => Number(b) - Number(a))
      .map((m) => ({ month: m, items: Array.isArray(monthMap[Number(m)] || monthMap[m]) ? (monthMap[Number(m)] || monthMap[m]) : [] }))
    // 月桶合并后 total = 各月条数之和；空月（后端理论不产生）自然计 0 但仍渲染，
    // 保证"导航点得进去"——和 M4 分类树"宁可见不可丢"同一个装配哲学
    const total = months.reduce((s, mo) => s + mo.items.length, 0)
    if (total > 0) out.push({ year: y, total, months })
  }
  return out
}

/** "2026年09月" → 展示文案（归档页月份标题） */
export function monthLabel(m) {
  return `${Number(m)} 月`
}

/**
 * 归档页本地过滤（M15 归档体验打磨）：标题包含关键词（忽略大小写）才保留，
 * 空月/空年整个剪掉——和 groupArchive"total=0 不渲染"同一口径。
 *
 * 为什么不接后端搜索接口：归档页数据口 /api/articles/archive 一次回全量
 * （毕设量级 <1000 篇，几百 KB 封顶），本地即时过滤零请求、零防抖、输入即变，
 * 比"跳回首页带 keyword"的动线短。数据量大时的正确演进是后端分页归档，
 * 记为论文"演进方向"，不在本期实现。
 *
 * @param {Array<{year:string,total:number,months:Array}>} sections groupArchive 产物
 * @param {string} keyword 过滤词（可空）
 * @returns 同构的新数组（不改入参——Vue 响应式列表里就地改会引发渲染期副作用）
 */
export function filterArchive(sections, keyword) {
  const kw = String(keyword ?? '').trim().toLowerCase()
  const list = Array.isArray(sections) ? sections : []
  if (!kw) return list.map((y) => ({ ...y, months: y.months.map((m) => ({ ...m })) }))
  const hit = (a) => String(a.title ?? '').toLowerCase().includes(kw)
  const out = []
  for (const y of list) {
    const months = []
    for (const m of y.months) {
      const items = (m.items || []).filter(hit)
      if (items.length) months.push({ month: m.month, items })
    }
    const total = months.reduce((s, m) => s + m.items.length, 0)
    if (total > 0) out.push({ year: y.year, total, months })
  }
  return out
}
