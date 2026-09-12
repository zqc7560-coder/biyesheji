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
