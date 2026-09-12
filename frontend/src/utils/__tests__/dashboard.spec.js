/**
 * M8 仪表盘纯函数单测：条形高度映射的边界（全零/尖峰/脏值）与指标卡完整性。
 * 页面本身不上 jsdom（决策沿用 vite.config.js：被测逻辑全部纯函数）。
 */
import { describe, it, expect } from 'vitest'
import { maxOf, barHeights, dashboardCards, shortDate, cacheStatsRows } from '../dashboard'

const pts = (...vals) => vals.map((v, i) => ({ date: `2026-09-1${i}`, value: v }))

describe('maxOf', () => {
  it('空序列/非数组/脏元素回 0，正数取最大', () => {
    expect(maxOf([])).toBe(0)
    expect(maxOf(null)).toBe(0)
    expect(maxOf([{ value: 'x' }, { value: 7 }, { value: -1 }])).toBe(7)
  })
})

describe('barHeights', () => {
  it('全零序列 → 每根都是最低可见高度 4%（0% 会让条形消失被误读为缺数据）', () => {
    const bars = barHeights(pts(0, 0, 0))
    expect(bars.every((b) => b.heightPct === 4)).toBe(true)
  })
  it('峰值归一 100%，比例线性（2/4 → 50%）', () => {
    const bars = barHeights(pts(2, 4))
    expect(bars[1].heightPct).toBe(100)
    expect(bars[0].heightPct).toBe(50)
  })
  it('极小非零值钳到 4% 下界（1/1000 → 0.1% 四舍五入 0%，必须被下限救起）', () => {
    const bars = barHeights(pts(1, 1000))
    expect(bars[0].heightPct).toBe(4)
    expect(bars[1].heightPct).toBe(100)
  })
  it('输出保留 date/value 原字段（渲染层不再回读原始序列）', () => {
    const bars = barHeights(pts(3))
    expect(bars[0]).toMatchObject({ date: '2026-09-10', value: 3 })
  })
})

describe('dashboardCards', () => {
  const counts = {
    articlePublished: 5, articleTotal: 8, userTotal: 3, userActive: 2,
    commentTotal: 11, commentPending: 2, aiCalls7d: 30, aiCost7d: '0.1235'
  }
  it('9 张卡全在（键序即渲染序），totalPv 来自独立入参', () => {
    const cards = dashboardCards(counts, 4200)
    expect(cards).toHaveLength(9)
    expect(cards.map((c) => c.key)).toEqual([
      'articlePublished', 'articleTotal', 'userTotal', 'userActive',
      'commentTotal', 'commentPending', 'aiCalls7d', 'aiCost7d', 'totalPv'
    ])
    expect(cards[8].value).toBe(4200)
  })
  it('待审评论 >0 亮 alert（侧栏红点同语义）；0 不亮', () => {
    expect(dashboardCards(counts, 0).find((c) => c.key === 'commentPending').alert).toBe(true)
    expect(dashboardCards({ ...counts, commentPending: 0 }, 0)
      .find((c) => c.key === 'commentPending').alert).toBe(false)
  })
  it('缺失/脏值降级为 0 而非 NaN（后端字段演进或半成品响应不炸看板）', () => {
    const cards = dashboardCards({ articleTotal: 'abc' }, null)
    expect(cards.find((c) => c.key === 'articleTotal').value).toBe(0)
    expect(cards.find((c) => c.key === 'totalPv').value).toBe(0)
  })
  it('成本 4 位小数原样（答辩量化数据不做展示层舍入戏法）', () => {
    expect(dashboardCards(counts, 0).find((c) => c.key === 'aiCost7d').value).toBe('0.1235')
  })
})

describe('shortDate', () => {
  it('ISO 日 → MM-DD；短串/空串原样', () => {
    expect(shortDate('2026-09-13')).toBe('09-13')
    expect(shortDate('9-13')).toBe('9-13')
    expect(shortDate('')).toBe('')
    expect(shortDate(null)).toBe('')
  })
})

describe('cacheStatsRows (M9)', () => {
  it('六键族全列出，缺失键回 0（后端 Redis 故障回空 Map 不炸页面）', () => {
    const rows = cacheStatsRows({})
    expect(rows.map((r) => r.key)).toEqual([
      'detailKeys', 'listKeys', 'listIndexMembers', 'pvKeys', 'pvDayKeys', 'evictRetryPending'])
    expect(rows.every((r) => r.value === 0)).toBe(true)
    const full = cacheStatsRows({ detailKeys: 3, listKeys: 2, listIndexMembers: 2,
      pvKeys: 1, pvDayKeys: 7, evictRetryPending: 0 })
    expect(full[0].value).toBe(3)
    expect(full.every((r) => !r.alert)).toBe(true)
  })
  it('补偿队列 >0 单独亮 alert（Redis 抖动信号，管理员必须看见）', () => {
    const rows = cacheStatsRows({ evictRetryPending: 2 })
    const retry = rows.find((r) => r.key === 'evictRetryPending')
    expect(retry.alert).toBe(true)
    expect(rows.filter((r) => r.alert)).toHaveLength(1)
  })
  it('null/脏值防御：非数值键降级 0 而非 NaN', () => {
    expect(cacheStatsRows(null)[0].value).toBe(0)
    expect(cacheStatsRows({ detailKeys: 'abc' })[0].value).toBe(0)
  })
})
