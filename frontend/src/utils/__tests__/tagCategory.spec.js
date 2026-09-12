/**
 * tagCategory.js 纯函数单测（M4）：树扁平化、标签云字号映射、标签输入切分。
 * 断言重点全在边界：空树、null 权重、越界权重、大小写去重、上限截断——
 * 这些是"页面看起来怪但没人查代码"的 bug 高发区。
 */
import { describe, it, expect } from 'vitest'
import { flattenCategoryTree, tagCloudFontSize, parseTagInput } from '../tagCategory'

describe('flattenCategoryTree 树→扁平选项', () => {
  const tree = [
    { id: 1, name: '技术', articleCount: 5, children: [
      { id: 3, name: '后端', articleCount: 2, children: [] },
      { id: 4, name: '前端', children: [] }
    ] },
    { id: 2, name: '生活', children: [] }
  ]

  it('先序遍历：父紧跟其子，depth 与缩进前缀一致', () => {
    const flat = flattenCategoryTree(tree)
    expect(flat.map((f) => f.id)).toEqual([1, 3, 4, 2])
    expect(flat[0]).toMatchObject({ depth: 0, label: '技术', articleCount: 5 })
    expect(flat[1]).toMatchObject({ depth: 1 })
    expect(flat[1].label).toContain('└ 后端')
    expect(flat[1].label.startsWith('　')).toBe(true)   // 全角空格缩进（HTML 不折叠）
  })

  it('articleCount 缺失/为 null 归 0（前端不背 undefined 渲染）', () => {
    const flat = flattenCategoryTree([{ id: 9, name: 'X', articleCount: null }])
    expect(flat[0].articleCount).toBe(0)
  })

  it('空树/null/undefined 全回 []（游客首启无数据的最早时刻）', () => {
    expect(flattenCategoryTree([])).toEqual([])
    expect(flattenCategoryTree(null)).toEqual([])
    expect(flattenCategoryTree(undefined)).toEqual([])
  })
})

describe('tagCloudFontSize 权重→字号', () => {
  it('端点映射：1→min、5→max、3→中位', () => {
    expect(tagCloudFontSize(1)).toBe('12.0px')
    expect(tagCloudFontSize(5)).toBe('28.0px')
    expect(tagCloudFontSize(3)).toBe('20.0px')
  })

  it('越界与非有限值钳制：NaN/负数/999 不许炸出荒谬字号', () => {
    expect(tagCloudFontSize(NaN)).toBe('20.0px')    // 不可用→中位（VO 注释约定）
    expect(tagCloudFontSize(null)).toBe('20.0px')
    expect(tagCloudFontSize(undefined)).toBe('20.0px')
    expect(tagCloudFontSize(-2)).toBe('12.0px')
    expect(tagCloudFontSize(999)).toBe('28.0px')
  })

  it('自定义 min/max 生效（暗色主题等场景的逃生门）', () => {
    expect(tagCloudFontSize(5, 10, 20)).toBe('20.0px')
  })
})

describe('parseTagInput 编辑器标签切分', () => {
  it('中英文逗号/空白混合分隔，去空项', () => {
    expect(parseTagInput('SpringBoot，后端, Java  云原生 ')).toEqual(['SpringBoot', '后端', 'Java', '云原生'])
  })

  it('大小写不敏感去重，保留首次出现的写法', () => {
    expect(parseTagInput('Java java JAVA')).toEqual(['Java'])
  })

  it('截断到 max（与后端 DTO max 10 同口径，先裁给即时反馈）', () => {
    const many = Array.from({ length: 15 }, (_, i) => `t${i}`).join(',')
    expect(parseTagInput(many)).toHaveLength(10)
    expect(parseTagInput('a,b', 1)).toEqual(['a'])
  })

  it('null/空串回 []（localStorage 草稿恢复时的常见脏值）', () => {
    expect(parseTagInput('')).toEqual([])
    expect(parseTagInput(null)).toEqual([])
    expect(parseTagInput('   ,， ')).toEqual([])
  })
})
