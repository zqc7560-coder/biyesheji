/**
 * M11 测试完善 · 前端零散纯函数补测（覆盖率收口轮）。
 *
 * 覆盖此前各模块"顺手导出但没进 spec"的残点：
 * 1) profile.avatarPickHint —— 前后端上传契约的一致性钉子：
 *    文案里的扩展名清单必须与 fileUpload.IMAGE_EXTENSIONS（后端
 *    FileTypeValidator 白名单的前端镜像）逐一对应、大小上限口径 5MB
 *    与后端 MinimalFileStorageService.MAX_SIZE 一致。文案改了忘了同步
 *    白名单（或反之）会让用户按提示传图却被 3001 拒——这条断言让 CI 抓。
 * 2) archive.padMonth/monthLabel —— M10 归档页小组件当时只测了 groupArchive，
 *    补上边界（12 月不进位、1 月补零、跨年标签）。
 */
import { describe, it, expect } from 'vitest'
import { avatarPickHint } from '../profile.js'
import { IMAGE_EXTENSIONS } from '../fileUpload.js'
import { padMonth, monthLabel } from '../archive.js'

describe('M11 头像提示与上传白名单同源契约', () => {
  it('提示文案逐扩展名覆盖 IMAGE_EXTENSIONS（新增白名单格式必须同步文案）', () => {
    const hint = avatarPickHint()
    for (const ext of IMAGE_EXTENSIONS) {
      expect(hint.toLowerCase()).toContain(ext.toLowerCase())
    }
    // 反向：文案不许宣传白名单外的格式（bmp 是用户误传重灾区）
    for (const ghost of ['bmp', 'svg', 'tiff']) {
      expect(hint.toLowerCase()).not.toContain(ghost)
    }
  })

  it('大小上限口径 5MB 与后端 MAX_SIZE=5*1024*1024 对齐（文案含 5MB 字样）', () => {
    expect(avatarPickHint()).toMatch(/5\s*MB/)
  })
})

describe('M11 归档月工具边界', () => {
  it('padMonth: 1~9 补零，10~12 原样', () => {
    expect(padMonth(1)).toBe('01')
    expect(padMonth(9)).toBe('09')
    expect(padMonth(10)).toBe('10')
    expect(padMonth(12)).toBe('12')
  })

  it('monthLabel: 数字月份转中文展示（1 月/12 月两端形态）', () => {
    expect(monthLabel('01')).toContain('1')
    expect(monthLabel('12')).toContain('12')
  })
})
