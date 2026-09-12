/**
 * M6 AI 纯函数测试（Vitest）—— 课题重点模块的前端决策逻辑。
 *
 * 为什么值得单测：aiButtonState 决定"AI 按钮是灰是亮、tooltip 说什么"，
 * summaryFailureAction 决定失败后是"重试/置灰/转手动"三条完全不同的交互分支。
 * 这些规则埋在 .vue 里只能手点验证，而"未配置"与"额度耗尽"的提示语混淆
 * 会直接让答辩演示卡壳——机械锁死优先级顺序是本轮的验收点。
 */
import { describe, it, expect } from 'vitest'
import {
  AI_CODES,
  aiButtonState,
  summaryFailureAction,
  summaryBackfillHint,
  keyStatusTag,
  isBuiltinConfig,
  logStatusTag,
  formatCost,
  // M7：AI 标签候选合并与统计页展示纯函数
  mergeAiTagCandidates,
  appendTagsToText,
  formatHitRate,
  statsWindow
} from '../ai'

/** status 接口的"一切正常"基线（各用例只覆盖自己关心的那个字段，避免测试间隐式耦合） */
const OK = { enabled: true, configured: true, defaultModel: 'deepseek-v4-flash', remainingToday: 47, dailyQuota: 50 }

describe('aiButtonState 按钮态推导', () => {
  it('状态未探到（null）时按钮灰置，提示加载中', () => {
    expect(aiButtonState(null, true)).toEqual({ disabled: true, reason: 'AI 状态加载中…' })
  })

  it('正常态 + 登录 → 可用', () => {
    expect(aiButtonState(OK, true)).toEqual({ disabled: false, reason: '' })
  })

  it('优先级：总开关 > 配置就绪 > 登录 > 额度（提示语必须对上真正拦住谁的那一关）', () => {
    // 三层同时坏时，只能报最靠前的"开关关了"
    const allBroken = { enabled: false, configured: false, remainingToday: 0, dailyQuota: 50 }
    expect(aiButtonState(allBroken, false).reason).toBe('AI 功能已被管理员关闭')
    // 开关开着、配置没配 → 报未配置
    expect(aiButtonState({ ...allBroken, enabled: true }, false).reason)
      .toBe('管理员尚未配置默认模型或未导入 API Key')
    // 配置就绪但未登录 → 报登录引导
    expect(aiButtonState(OK, false).reason).toBe('登录后即可使用 AI 功能')
    // 登录且额度耗尽 → 报额度（末位）
    expect(aiButtonState({ ...OK, remainingToday: 0 }, true).reason)
      .toBe('今日 AI 额度已用完（50 次/日）')
  })

  it('remainingToday 为 null（游客口径）时不做额度判断，不误灰', () => {
    // 后端对游客不返回额度字段；若此处用 <=0 判定，null 会被误当成"额度耗尽"
    expect(aiButtonState({ ...OK, remainingToday: null }, true).disabled).toBe(false)
  })

  it('剩 1 次仍可用（边界：>0 即放行）', () => {
    expect(aiButtonState({ ...OK, remainingToday: 1 }, true).disabled).toBe(false)
  })
})

describe('summaryFailureAction 失败处置指令', () => {
  it('4001/4004 → disable（再点也没用，交回上层重探状态让按钮变灰）', () => {
    for (const code of [AI_CODES.NOT_CONFIGURED, AI_CODES.DISABLED]) {
      const act = summaryFailureAction({ code, message: 'm' })
      expect(act.action).toBe('disable')
    }
  })

  it('4003 额度 → manual（引导手动填写，明天再来）', () => {
    const act = summaryFailureAction({ code: AI_CODES.QUOTA_EXCEEDED, message: '超限' })
    expect(act.action).toBe('manual')
    expect(act.message).toContain('手动填写')
  })

  it('4002/4005/未知码/空错误 → retry（保持按钮可用）', () => {
    for (const err of [
      { code: AI_CODES.CALL_FAILED }, { code: AI_CODES.BAD_OUTPUT },
      { code: 500 }, { code: undefined }, null, undefined
    ]) {
      expect(summaryFailureAction(err).action).toBe('retry')
    }
  })
})

describe('summaryBackfillHint 回填提示', () => {
  it('无结果/空摘要 → null（绝不回填空串覆盖用户已写内容）', () => {
    expect(summaryBackfillHint(null)).toBeNull()
    expect(summaryBackfillHint({ summary: '' })).toBeNull()
  })

  it('fallback 优先于 fromCache（兜底产物质量有限，必须提示人工润色）', () => {
    expect(summaryBackfillHint({ summary: 's', fallback: true, fromCache: true })).toContain('兜底')
  })

  it('命中缓存 → 提示未消耗新额度', () => {
    expect(summaryBackfillHint({ summary: 's', fallback: false, fromCache: true })).toContain('缓存')
  })

  it('正常新生成 → null（不打扰）', () => {
    expect(summaryBackfillHint({ summary: 's', fallback: false, fromCache: false })).toBeNull()
  })
})

describe('配置行 / 日志行展示辅助', () => {
  it('keyStatusTag：已导入绿标带掩码；未导入黄标提醒管理员', () => {
    expect(keyStatusTag({ keyImported: true, apiKeyMask: 'sk-****3456' }))
      .toEqual({ type: 'success', label: '已导入 sk-****3456' })
    expect(keyStatusTag({ keyImported: false, apiKeyMask: '__PENDING_KEY_IMPORT__' }))
      .toEqual({ type: 'warning', label: '__PENDING_KEY_IMPORT__' })
    // 掩码缺失时兜底成固定文案，不渲染出 "undefined"
    expect(keyStatusTag({ keyImported: false }).label).toBe('未导入Key')
  })

  it('isBuiltinConfig：仅数字/字符串 1 判内置（后端 Integer，JSON 里可能是 "1"）', () => {
    expect(isBuiltinConfig({ isBuiltin: 1 })).toBe(true)
    expect(isBuiltinConfig({ isBuiltin: '1' })).toBe(true)
    expect(isBuiltinConfig({ isBuiltin: 0 })).toBe(false)
    expect(isBuiltinConfig({})).toBe(false)
  })

  it('logStatusTag：1 成功 / 3 重试后成功（黄）/ 2 失败（红）/ 脏值落失败', () => {
    expect(logStatusTag(1)).toEqual({ type: 'success', label: '成功' })
    expect(logStatusTag(3)).toEqual({ type: 'warning', label: '重试后成功' })
    expect(logStatusTag(2)).toEqual({ type: 'danger', label: '失败' })
    expect(logStatusTag(null).type).toBe('danger')
  })

  it('formatCost：null→破折号；0→0；极小费用保留 6 位（厘分之下答辩可追问）', () => {
    expect(formatCost(null)).toBe('—')
    expect(formatCost(0)).toBe('0')
    expect(formatCost('0.000123')).toBe('0.000123')
    expect(formatCost(0.1234567)).toBe('0.1235')
  })
})

describe('M7: AI 标签候选与统计页纯函数', () => {
  it('mergeAiTagCandidates：与编辑器已有标签忽略大小写去重；existingTagIds×字典→已有角标', () => {
    const known = [{ id: 5, name: 'Redis' }, { id: 8, name: '缓存' }]
    const r = mergeAiTagCandidates(['SpringBoot', 'Redis', 'java'], [5], known, ['Java'])
    // "java" 与已有的 "Java" 大小写不敏感重合被剔；Redis 的 id=5 在 existingIds → 角标
    expect(r).toEqual([
      { name: 'SpringBoot', existing: false },
      { name: 'Redis', existing: true },
    ])
  })

  it('mergeAiTagCandidates：全缺省入参不抛（防御 null 响应体）', () => {
    expect(mergeAiTagCandidates(null, undefined, null, undefined)).toEqual([])
  })

  it('appendTagsToText：去重追加+末尾补逗；大小写重合不重复挂', () => {
    expect(appendTagsToText('Java, Go', ['Go', 'Rust'])).toBe('Java, Go, Rust, ')
    expect(appendTagsToText('', ['A', 'B'])).toBe('A, B, ')
    expect(appendTagsToText('Java', ['java'])).toBe('Java, ')
  })

  it('formatHitRate：0.42→42.0%；null→破折号；1→100.0%', () => {
    expect(formatHitRate(0.42)).toBe('42.0%')
    expect(formatHitRate(1)).toBe('100.0%')
    expect(formatHitRate(null)).toBe('—')
  })

  it('statsWindow：7d 取含今天共 7 天；跨月回退正确；未知 preset 回 30d 口径', () => {
    const w = statsWindow('7d', new Date(2026, 8, 13))   // month 从 0 计 → 2026-09-13
    expect(w).toEqual({ dateFrom: '2026-09-07', dateTo: '2026-09-13' })
    expect(statsWindow('30d', new Date(2026, 8, 2)).dateFrom).toBe('2026-08-04')
    expect(statsWindow('3d', new Date(2026, 8, 13)).dateFrom).toBe('2026-08-15')
  })
})
