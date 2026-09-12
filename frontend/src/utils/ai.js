/**
 * AI 模块纯函数（M6）——编辑器按钮态与后台配置页的决策逻辑。
 * 拆纯函数进 Vitest 的理由与 M3/M5 相同：这些规则决定"用户看到的按钮是灰是亮、
 * 失败提示说什么"，埋在 .vue 里只能手点，机械锁死更划算。
 */

/** AI 降级错误码族（后端 BizCode 4xxx；code 是契约，文案会变） */
export const AI_CODES = {
  NOT_CONFIGURED: 4001,
  CALL_FAILED: 4002,
  QUOTA_EXCEEDED: 4003,
  DISABLED: 4004,
  BAD_OUTPUT: 4005
}

/**
 * 由 /api/ai/status 响应推导编辑器 AI 按钮态。
 * 优先级：总开关 > 配置就绪 > 额度（与后端降级矩阵的检查顺序一致，
 * 提示语才能对上"真正拦住了谁"）。
 * @returns {{disabled:boolean, reason:string}} reason 直接进 el-tooltip
 */
export function aiButtonState(status, loggedIn) {
  if (!status) return { disabled: true, reason: 'AI 状态加载中…' }
  if (!status.enabled) return { disabled: true, reason: 'AI 功能已被管理员关闭' }
  if (!status.configured) return { disabled: true, reason: '管理员尚未配置默认模型或未导入 API Key' }
  if (!loggedIn) return { disabled: true, reason: '登录后即可使用 AI 功能' }
  if (status.remainingToday != null && status.remainingToday <= 0) {
    return { disabled: true, reason: `今日 AI 额度已用完（${status.dailyQuota} 次/日）` }
  }
  return { disabled: false, reason: '' }
}

/**
 * 摘要失败错误 → 编辑器内的处置指令（拦截器已 toast，这里管"按钮/表单还能不能抢救"）。
 * - 4001/4004：按钮置灰并带原因（再点也没用，别让用户反复碰壁）
 * - 4003：额度文案+引导手动填写（明天再来）
 * - 4002/4005：可重试——保持按钮可用，提示稍后再试或手动填写
 */
export function summaryFailureAction(err) {
  const code = err?.code
  if (code === AI_CODES.NOT_CONFIGURED || code === AI_CODES.DISABLED) {
    return { action: 'disable', message: err.message }
  }
  if (code === AI_CODES.QUOTA_EXCEEDED) {
    return { action: 'manual', message: '今日 AI 额度已用完，可手动填写摘要，明天再来' }
  }
  return { action: 'retry', message: 'AI 暂不可用，请稍后重试或手动填写摘要' }
}

/**
 * AI 摘要结果 → 是否可回填（防"空摘要覆盖用户已写内容"）。
 * fallback 结果也回填（用户可改），但要附提示——AI 拒答兜底的截取摘要质量有限。
 */
export function summaryBackfillHint(result) {
  if (!result || !result.summary) return null
  return result.fallback
    ? '模型未按要求输出，已用正文截取兜底，请人工润色'
    : result.fromCache
      ? '命中缓存（内容与上次生成时一致，未消耗新额度）'
      : null
}

/**
 * 配置行的 Key 状态标签（后台表格"Key 状态"列）：
 * 三态：未导入（黄）/ 已导入（绿）/ 掩码本身为空（异常兜底灰）
 */
export function keyStatusTag(row) {
  if (row.keyImported) return { type: 'success', label: `已导入 ${row.apiKeyMask || ''}`.trim() }
  return { type: 'warning', label: row.apiKeyMask || '未导入Key' }
}

/** 内置行判定（前端隐藏删除按钮/禁用身份输入框——后端 4006 才是真防线） */
export function isBuiltinConfig(row) {
  return Number(row.isBuiltin) === 1
}

/** 日志成败状态标签（1 成功 / 2 失败 / 3 重试后成功）——日志页配色单一事实源 */
export function logStatusTag(status) {
  if (status === 1) return { type: 'success', label: '成功' }
  if (status === 3) return { type: 'warning', label: '重试后成功' }
  return { type: 'danger', label: '失败' }
}

/** 成本格式化：null→'—'；0→'0'；小数费用保留 6 位有效（厘分之下答辩可追问） */
export function formatCost(costEst) {
  if (costEst == null) return '—'
  const n = Number(costEst)
  if (n === 0) return '0'
  return n < 0.001 ? n.toFixed(6) : n.toFixed(4)
}

/**
 * ---------- M7：AI 标签候选与成本统计 ----------
 */

/**
 * AI 候选标签 → 可勾选清单（M7；ArticleEditView 的"AI 标签"弹窗数据源）。
 *
 * 纯函数职责三条规则：
 * · 与编辑器现有标签去重（忽略大小写——与后端 tag uk_name 的 CI 排序规则同口径，
 *   否则用户勾了 "java" 又留着自己的 "Java"，保存时被静默合并、数量对不上）；
 * · existingTagIds（后端实时回查的已入库 id）结合 knownTags（编辑器已拉取的
 *   id↔name 清单，M4 补全数据同源）映射成名称集合，命中的候选标 existing=true
 *   → 弹窗里显示"已有"角标，让用户勾选时预判标签云会不会长新词；
 * · 只过滤不排序不截断——服务端合法化（§5.2）已保证 ≤6 个且有序，前端重排是画蛇添足。
 * @param {string[]} aiTags 后端合法化后的候选
 * @param {number[]} existingTagIds 后端回查的已入库标签 id
 * @param {{id:number,name:string}[]} knownTags 编辑器已加载的标签清单（id↔name 字典源）
 * @param {string[]} currentNames 编辑器输入框现有标签
 * @returns {{name:string, existing:boolean}[]}
 */
export function mergeAiTagCandidates(aiTags, existingTagIds, knownTags, currentNames) {
  const owned = new Set((currentNames || []).map((n) => String(n).toLowerCase()))
  const idSet = new Set((existingTagIds || []).map(Number))
  const existedNames = new Set(
    (knownTags || []).filter((t) => idSet.has(Number(t.id))).map((t) => String(t.name).toLowerCase()))
  return (aiTags || [])
    .filter((t) => !owned.has(String(t).toLowerCase()))
    .map((t) => ({ name: t, existing: existedNames.has(String(t).toLowerCase()) }))
}

/**
 * 勾选确认后写回 tagsText：现有串 + 勾中的候选，逗号分隔（编辑器单一事实源是文本框）。
 * 末尾统一补一个 ", "（保持继续手输的手感，与 M4 onPickTag 同款约定）。
 */
export function appendTagsToText(currentText, pickedNames) {
  const parts = String(currentText || '').split(/[,，\s]+/).map((s) => s.trim()).filter(Boolean)
  for (const p of pickedNames || []) {
    if (!parts.some((x) => x.toLowerCase() === String(p).toLowerCase())) parts.push(p)
  }
  return parts.length ? parts.join(', ') + ', ' : ''
}

/**
 * 成本统计页：命中率小数(0~1) → 展示文案。null/NaN 落 "—"（新装库无日志的初始态）。
 */
export function formatHitRate(rate) {
  const n = Number(rate)
  if (rate == null || Number.isNaN(n)) return '—'
  return (n * 100).toFixed(1) + '%'
}

/**
 * 统计页日期窗快捷计算：'7d'|'30d'|'90d' → {dateFrom,dateTo}（ISO 字符串，后端契约）。
 * 用本地日期而非 toISOString（UTC 偏移会让东八区的"今天"在北京时间 0-8 点显示成昨天）。
 */
export function statsWindow(preset, today = new Date()) {
  const to = toIsoDate(today)
  const days = { '7d': 6, '30d': 29, '90d': 89 }[preset] ?? 29
  const from = new Date(today)
  from.setDate(from.getDate() - days)
  return { dateFrom: toIsoDate(from), dateTo: to }
}

/** Date → 本地 ISO 日期（yyyy-MM-dd，非 UTC 化） */
function toIsoDate(d) {
  const p = (n) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`
}
