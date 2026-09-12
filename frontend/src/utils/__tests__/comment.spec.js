/**
 * 评论纯函数单测（M5）—— 与后端 CommentApiM5Test 的契约镜像：
 * 状态映射逐值、嵌套排序稳定性、@前缀、相对时间阈值、长度钳制口径。
 * 相对路径导入（M0 台账坑#3：混用别名与相对路径会让 Vite 视为两个实例）。
 */
import { describe, it, expect } from 'vitest'
import {
  COMMENT_STATUS_LABELS,
  COMMENT_STATUS_TAG,
  sortCommentTree,
  replyPrefix,
  relativeTime,
  commentLengthCheck,
  COMMENT_MAX_LENGTH
} from '../comment.js'

describe('审核状态映射', () => {
  it('与后端常量逐值对齐（0待审/1通过/2屏蔽），着色映射齐全', () => {
    expect(COMMENT_STATUS_LABELS).toEqual({ 0: '待审核', 1: '已通过', 2: '已屏蔽' })
    expect(Object.keys(COMMENT_STATUS_TAG)).toEqual(['0', '1', '2'])
  })
})

describe('sortCommentTree', () => {
  it('顶级按时间正序；乱序输入被稳定化；回复按 id 升序', () => {
    const list = [
      { id: 3, createdAt: '2026-09-11T10:00:00', replies: [{ id: 6 }, { id: 5 }] },
      { id: 1, createdAt: '2026-09-10T09:00:00', replies: [] },
      { id: 2, createdAt: '2026-09-10T12:00:00' }
    ]
    const out = sortCommentTree(list)
    expect(out.map((c) => c.id)).toEqual([1, 2, 3])
    expect(out[2].replies.map((r) => r.id)).toEqual([5, 6])
    expect(out[1].replies).toBeUndefined() // 无 replies 字段的顶级不动它
  })

  it('非数组输入防御为空数组；不修改入参', () => {
    const frozen = [{ id: 2, createdAt: 'b' }, { id: 1, createdAt: 'a' }]
    const out = sortCommentTree(frozen)
    expect(frozen.map((c) => c.id)).toEqual([2, 1]) // 原数组次序未被就地改写
    expect(sortCommentTree(null)).toEqual([])
    expect(out.length).toBe(2)
  })
})

describe('replyPrefix', () => {
  it('有 replyTo 时输出"x 回复 "前缀；无则空串', () => {
    expect(replyPrefix({ replyTo: { nickname: '张三' } })).toBe('张三 回复 ')
    expect(replyPrefix({ replyTo: {} })).toBe('未知用户 回复 ')
    expect(replyPrefix({})).toBe('')
    expect(replyPrefix(null)).toBe('')
  })
})

describe('relativeTime', () => {
  const now = new Date('2026-09-12T12:00:00Z').getTime()
  const at = (iso) => relativeTime(iso, now)

  it('阈值链：刚刚/分钟/小时/天/绝对日期', () => {
    expect(at('2026-09-12T11:59:30Z')).toBe('刚刚')
    expect(at('2026-09-12T11:57:00Z')).toBe('3 分钟前')
    expect(at('2026-09-12T09:00:00Z')).toBe('3 小时前')
    expect(at('2026-09-09T12:00:00Z')).toBe('3 天前')
    expect(at('2026-06-01T12:00:00Z')).toBe('2026-06-01')
  })

  it('空值/坏值防御；未来时间钳到"刚刚"（客户端时钟偏差保护）', () => {
    expect(at('')).toBe('')
    expect(relativeTime(null)).toBe('')
    expect(at('不是时间')).toBe('不是时间')
    expect(at('2026-09-12T13:00:00Z')).toBe('刚刚')
  })
})

describe('commentLengthCheck', () => {
  it('口径与后端 DTO 对齐：trim 后 1~1000；超 1000 标 over', () => {
    expect(commentLengthCheck('  ').ok).toBe(false)
    expect(commentLengthCheck('你好').len).toBe(2)
    expect(commentLengthCheck('字'.repeat(COMMENT_MAX_LENGTH)).ok).toBe(true)
    const over = commentLengthCheck('字'.repeat(COMMENT_MAX_LENGTH + 1))
    expect(over.over).toBe(true)
    expect(over.ok).toBe(false)
  })
})
