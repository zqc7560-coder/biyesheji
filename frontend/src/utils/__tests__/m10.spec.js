/**
 * M10 前端纯函数单测：归档整形（archive.js）+ 资料/改密校验（profile.js）
 * + 站点门面映射（site.js 的 mapPublicConfig）。
 *
 * 断言策略沿用 M3~M9：只测规则不测渲染（无 jsdom，环境约束）；边界优先——
 * 空树、非法键、越界长度这些"页面怪一下但没人查代码"的场景。
 */
import { describe, it, expect } from 'vitest'
import { padMonth, groupArchive, monthLabel } from '../archive'
import { validateProfile, validatePasswordChange } from '../profile'
import { mapPublicConfig } from '../site'

describe('padMonth 月份归一', () => {
  it('"9"→"09"、"12"→"12"；非法月份判 null', () => {
    expect(padMonth('9')).toBe('09')
    expect(padMonth(12)).toBe('12')
    expect(padMonth('0')).toBeNull()
    expect(padMonth('13')).toBeNull()
    expect(padMonth('abc')).toBeNull()
  })
})

describe('groupArchive 归档树整形', () => {
  it('年/月显式降序 + 月度桶计数汇总', () => {
    const tree = {
      '2025': { '12': [{ id: 1, title: '旧' }], '03': [] },
      '2026': { '01': [{ id: 2, title: '甲' }, { id: 3, title: '乙' }], '09': [{ id: 4, title: '丙' }] }
    }
    const out = groupArchive(tree)
    expect(out.map((y) => y.year)).toEqual(['2026', '2025'])
    expect(out[0].total).toBe(3)
    expect(out[0].months.map((m) => m.month)).toEqual(['09', '01'])
    // 03 月空桶：total=0 的年份仍保留 2025（它有 12 月的一篇），但 03 空月计入 months（"宁可见不可丢"）
    expect(out[1].months.map((m) => m.month)).toEqual(['12', '03'])
    expect(out[1].total).toBe(1)
  })

  it('全空年份剔除 + 非法年键过滤 + null/非对象安全回 []', () => {
    expect(groupArchive({ '2026': { '09': [] } })).toEqual([])
    expect(groupArchive({ 'xx': { '09': [{ id: 1 }] } })).toEqual([])
    expect(groupArchive(null)).toEqual([])
    expect(groupArchive('not-a-tree')).toEqual([])
  })

  it('数字型月键（后端理论上回字符串，JSON 反序列化后是键名，但数值桶要能命中）', () => {
    const out = groupArchive({ '2026': { 9: [{ id: 7, title: 'T' }] } })
    expect(out[0].months[0].items.length).toBe(1)
  })

  it('monthLabel 展示文案', () => {
    expect(monthLabel('09')).toBe('9 月')
    expect(monthLabel('12')).toBe('12 月')
  })
})

describe('validateProfile 资料表单校验（对齐后端 ProfileUpdateDTO）', () => {
  it('合法输入零错误', () => {
    expect(validateProfile({ nickname: '张三', email: 'a@b.co', bio: 'hi' })).toEqual({})
  })
  it('空昵称/坏邮箱/超长 bio 各自点名', () => {
    const e = validateProfile({ nickname: '  ', email: 'no-at', bio: 'x'.repeat(501) })
    expect(e.nickname).toBeTruthy()
    expect(e.email).toBeTruthy()
    expect(e.bio).toBeTruthy()
  })
  it('undefined/空表单全红不抛异常', () => {
    const e = validateProfile(undefined)
    expect(Object.keys(e)).toEqual(['nickname', 'email'])
  })
})

describe('validatePasswordChange 改密校验（对齐后端 @Pattern）', () => {
  it('合法：旧密码填了 + 新密码≥8含字母数字 + 确认一致', () => {
    expect(validatePasswordChange({ oldPassword: 'Aa123456', newPassword: 'Bb654321', confirmPassword: 'Bb654321' })).toEqual({})
  })
  it('太短/纯字母/与旧相同/确认不一致 各有专属文案', () => {
    expect(validatePasswordChange({ oldPassword: 'x', newPassword: 'Ab1', confirmPassword: 'Ab1' }).newPassword).toBeTruthy()
    expect(validatePasswordChange({ oldPassword: 'x', newPassword: 'abcdefgh', confirmPassword: 'abcdefgh' }).newPassword).toBeTruthy()
    expect(validatePasswordChange({ oldPassword: 'Aa123456', newPassword: 'Aa123456', confirmPassword: 'Aa123456' }).newPassword)
      .toContain('旧密码')
    expect(validatePasswordChange({ oldPassword: 'x', newPassword: 'Ab123456', confirmPassword: 'Ab123457' }).confirmPassword).toBeTruthy()
  })
  it('未填 confirmPassword 字段（undefined）不触发确认错（两字段表单场景兼容）', () => {
    expect(validatePasswordChange({ oldPassword: 'x', newPassword: 'Ab123456' })).toEqual({})
  })
})

describe('mapPublicConfig 站点门面映射', () => {
  it('空/非对象回默认名（首屏永不空白）', () => {
    expect(mapPublicConfig(null).name).toBe('个人博客系统')
    expect(mapPublicConfig('x').logo).toBe('')
    expect(mapPublicConfig({}).name).toBe('个人博客系统')
  })
  it('空白站点名回默认；正常键逐字取 site_name/site_logo/site_icp', () => {
    expect(mapPublicConfig({ site_name: '  ' }).name).toBe('个人博客系统')
    expect(mapPublicConfig({ site_name: '小站', site_logo: '/uploads/a.png', site_icp: 'ICP备1号' }))
      .toEqual({ name: '小站', logo: '/uploads/a.png', icp: 'ICP备1号' })
  })
})
