/**
 * 分类/标签展示的纯函数（M4）—— 树扁平化、标签云字号映射。
 *
 * 设计原则（与 menu.js/markdown.js 同一族）：纯函数 + 纯数据，不 import vue/pinia，
 * 因此能直接在 node 环境的 Vitest 里断言，不为展示逻辑背 jsdom 成本。
 * 为什么展示逻辑值得单测：树→缩进选项表、权重→字号这类"映射"最容易出现
 * 边界 bug（空 children、权重越界、null 计数），而它们一旦错了在页面上只是
 * "看起来怪"，没人会去查函数——测试是唯一守得住的地方。
 */

/**
 * 分类树 → el-select 用的扁平选项数组（带缩进前缀表达层级）。
 * @param {Array} nodes 后端 GET /api/categories 的树
 * @param {number} depth 内部递归参数：当前层级（0 起）
 * @returns {Array<{id:number,name:string,label:string,depth:number,articleCount:number}>}
 *  先序遍历顺序（父紧跟其子，与导航视觉一致）；label 用全角空格缩进（HTML 会折叠普通空格）。
 */
export function flattenCategoryTree(nodes, depth = 0) {
  const out = []
  for (const n of nodes || []) {
    out.push({
      id: n.id,
      name: n.name,
      depth,
      articleCount: n.articleCount ?? 0,
      label: '　'.repeat(depth) + (depth > 0 ? '└ ' : '') + n.name
    })
    out.push(...flattenCategoryTree(n.children, depth + 1))
  }
  return out
}

/**
 * 标签云权重 → 字号映射。
 * @param {number} weight 后端 cloud 模式给的 [1,5] 归一权重（非云模式传 null/undefined → 按中位 3）
 * @param {number} minPx 权重 1 对应字号（默认 12）
 * @param {number} maxPx 权重 5 对应字号（默认 28）
 * @returns {string} CSS font-size 值（如 "19.6px"）
 * 越界的 weight 钳制回 [1,5]：后端契约是闭区间，但前端不能信任它——
 * NaN/负数/大于 5 都会出现（历史数据、后端算法演进），映射函数必须全输入有界。
 */
export function tagCloudFontSize(weight, minPx = 12, maxPx = 28) {
  const w = Number.isFinite(weight) ? Math.min(5, Math.max(1, weight)) : 3
  const ratio = (w - 1) / 4                      // [1,5] → [0,1] 线性归一
  return `${(minPx + ratio * (maxPx - minPx)).toFixed(1)}px`
}

/**
 * 把文章编辑器的标签输入文本切成数组（从 ArticleEditView 内联逻辑提升为可测函数）。
 * 逗号（中英文）/空白分隔、去空项、保序去重、上限 max（后端 DTO 也是 max 10，
 * 前端先裁一刀给即时反馈，后端仍是最终裁判）。
 */
export function parseTagInput(text, max = 10) {
  const seen = new Set()
  const out = []
  for (const raw of String(text ?? '').split(/[,，\s]+/)) {
    const t = raw.trim()
    if (!t) continue
    const key = t.toLowerCase()   // 大小写不敏感去重："Java" 与 "java" 视为同一个（后端按精确名复用，撞不出错但视觉重复）
    if (seen.has(key)) continue
    seen.add(key)
    out.push(t)
    if (out.length >= max) break
  }
  return out
}
