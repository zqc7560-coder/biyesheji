/**
 * M8 上传预检与图片语法纯函数单测。
 * 覆盖安全边界：扩展名白名单（与后端 FileTypeValidator 同源清单）、5MB 体积闸、
 * markdown 语法的 alt 转义（文件名可控，不能让 "]" 撕裂 ![]() 结构）。
 */
import { describe, it, expect } from 'vitest'
import {
  IMAGE_EXTENSIONS, MAX_FILE_BYTES, UPLOAD_BIZ_TYPES,
  fileExtension, precheckImage, toMarkdownImage, assertUploadResult
} from '../fileUpload'

const file = (name, size = 1024) => ({ name, size })

describe('fileExtension', () => {
  it('大小写归一 / 无扩展名与尾点回空串', () => {
    expect(fileExtension('A.PNG')).toBe('png')
    expect(fileExtension('noext')).toBe('')
    expect(fileExtension('bad.')).toBe('')
    expect(fileExtension(null)).toBe('')
  })
  it('双扩展名取末段（payload.png.html 必须以 html 被拒）', () => {
    expect(fileExtension('payload.png.html')).toBe('html')
  })
})

describe('precheckImage', () => {
  it('白名单四族通过；svg/exe 拒绝（svg 是存储型 XSS 惯用载体，白名单里没有它是决策而非疏漏）', () => {
    for (const ext of ['png', 'jpg', 'jpeg', 'webp', 'gif']) {
      expect(precheckImage(file(`a.${ext}`)).ok).toBe(true)
    }
    expect(precheckImage(file('x.svg')).ok).toBe(false)
    expect(precheckImage(file('x.exe')).ok).toBe(false)
  })
  it('恰好 5MB 放行、+1 字节拒绝（边界与后端 MAX_SIZE 同一常量语义）', () => {
    expect(precheckImage(file('a.png', MAX_FILE_BYTES)).ok).toBe(true)
    expect(precheckImage(file('a.png', MAX_FILE_BYTES + 1)).ok).toBe(false)
    expect(precheckImage(file('a.png', MAX_FILE_BYTES + 1)).message).toContain('5MB')
  })
  it('空入参防御', () => {
    expect(precheckImage(null).ok).toBe(false)
    expect(precheckImage(undefined).message).toBe('未选择文件')
  })
  it('清单常量与后端约定一致（改白名单时本测试先红，提醒双端同步）', () => {
    expect(IMAGE_EXTENSIONS).toEqual(['png', 'jpg', 'jpeg', 'webp', 'gif'])
    expect(UPLOAD_BIZ_TYPES).toEqual(['avatar', 'cover', 'article'])
  })
})

describe('toMarkdownImage', () => {
  it('基本形态：去扩展名做 alt，URL 原样进圆括号', () => {
    expect(toMarkdownImage('/uploads/2026/09/ab.png', '架构总览.png'))
      .toBe('![架构总览](/uploads/2026/09/ab.png)')
  })
  it('alt 里的中括号被摘除（防止文件名撕裂 ![]() 语法结构）', () => {
    const md = toMarkdownImage('/u/a.png', 'evil][onclick=x.png')
    expect(md).toBe('![evilonclick=x](/u/a.png)')
    // 结果必须仍是"恰好一对中括号"的可解析语法
    expect(md.match(/[\[\]]/g)).toEqual(['[', ']', '(', ')'].slice(0, 2))
  })
  it('无名文件回退占位 alt「图片」', () => {
    expect(toMarkdownImage('/u/a.png', '')).toBe('![图片](/u/a.png)')
    expect(toMarkdownImage('/u/a.png', null)).toBe('![图片](/u/a.png)')
  })
})

describe('assertUploadResult', () => {
  it('/uploads/ 前缀的合法载荷放行', () => {
    const r = { url: '/uploads/2026/09/x.png', fileId: 3, size: 10 }
    expect(assertUploadResult(r)).toBe(r)
  })
  it('缺 url / 非 /uploads 前缀 / 非对象 → 抛错（网关异常体不静默穿透）', () => {
    expect(() => assertUploadResult(null)).toThrow()
    expect(() => assertUploadResult({ fileId: 1 })).toThrow()
    expect(() => assertUploadResult({ url: 'http://evil/x.png' })).toThrow()
  })
})
