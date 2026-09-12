/**
 * M3 前端纯逻辑单测（Vitest，node 环境）：
 * 1. renderMarkdown —— XSS 载荷在 markdown-it(html:false)+validateLink 白名单下失活
 *    （NFR-05 前端半边；DOMPurify 在 node isSupported=false 被跳过，见 markdown.js 头注释）；
 * 2. extractToc/injectTocIds —— TOC 契约；
 * 3. decideSaveFailure/resolveEditorSeed —— 乐观锁冲突与草稿恢复的决策表（D-8）。
 */
import { describe, it, expect } from 'vitest'
import { renderMarkdown, extractToc, injectTocIds } from '../markdown.js'
import { decideSaveFailure, resolveEditorSeed, localDraftKey, VERSION_CONFLICT } from '../articleFlow.js'

describe('renderMarkdown: XSS 双闸（html:false + 链接协议白名单）', () => {
  it('原始 HTML 标签被转义成文本，不产出可执行元素', () => {
    const html = renderMarkdown('正文 <script>alert(1)</script> <img src=x onerror=alert(1)>')
    // 判"是否成为活元素"而非"是否含危险字样"：html:false 下载荷以惰性文本
    // （&lt;script&gt;…）保留是既定行为，用户写博客引用代码片段也要看得见原文
    expect(html).not.toMatch(/<script/i)
    expect(html).not.toMatch(/<img[^>]*onerror/i)
    expect(html).toContain('&lt;script&gt;')
  })

  it('javascript: 协议链接被白名单击毙：不成链（惰性字面文本保留是转义语义）', () => {
    const html = renderMarkdown('[点我](javascript:alert(1))')
    expect(html).not.toContain('<a')
  })

  it('data:text/html 伪协议同样拒（黑名单永远漏，白名单只有 http/https/mailto/相对）', () => {
    const html = renderMarkdown('[毒](data:text/html;base64,PHNjcmlwdD5hbGVydCgxKTwvc2NyaXB0Pg==)')
    expect(html).not.toContain('<a')
  })

  it('正常链接与站内相对链接放行', () => {
    expect(renderMarkdown('[官网](https://example.com)')).toContain('href="https://example.com"')
    expect(renderMarkdown('[站内](/articles/42)')).toContain('href="/articles/42"')
    expect(renderMarkdown('[锚点](#sec)')).toContain('href="#sec"')
  })

  it('基础 Markdown 渲染：标题/粗体/列表/围栏代码块', () => {
    const html = renderMarkdown('# 标题\n\n**粗**\n\n- 甲\n- 乙\n\n```\ncode()\n```')
    expect(html).toContain('<h1>标题</h1>')
    expect(html).toContain('<strong>粗</strong>')
    expect(html).toContain('<li>甲</li>')
    expect(html).toContain('<code')
  })

  it('空/undefined 输入安全返回（详情页 loading 前不炸）', () => {
    expect(renderMarkdown(undefined)).toBe('')
    expect(renderMarkdown('')).toBe('')
  })
})

describe('TOC: 提取与锚点回写', () => {
  it('h2/h3 按出现序提取，行内标记剥成纯文本', () => {
    const html = renderMarkdown('## 第一节\n\n### 1.1 小节\n\n## **粗体标题**')
    const toc = extractToc(html)
    expect(toc).toHaveLength(3)
    expect(toc[0]).toMatchObject({ level: 2, text: '第一节', id: 'toc-0' })
    expect(toc[2].text).toBe('粗体标题')       // <strong> 残留被剥
    expect(toc[1].level).toBe(3)
  })

  it('h1 不进目录（留给文章标题），injectTocIds 依序回写 id', () => {
    const html = renderMarkdown('# 大标题\n\n## 小节甲')
    const toc = extractToc(html)
    expect(toc).toHaveLength(1)
    const patched = injectTocIds(html, toc)
    expect(patched).toContain('<h2 id="toc-0">')
    expect(patched).toMatch(/<h1[^>]*>大标题/)   // h1 不带 toc id
  })
})

describe('articleFlow: 保存失败决策（D-8）', () => {
  it('2004 乐观锁冲突 → 弹框询问，绝不自动 reload 吞本地编辑', () => {
    const d = decideSaveFailure({ code: VERSION_CONFLICT }, { hasLocalDraft: true })
    expect(d.action).toBe('ask-conflict')
    expect(d.message).toContain('服务器')
  })

  it('2001 已删/无权 → toast 文案直出', () => {
    const d = decideSaveFailure({ code: 2001, message: '文章不存在或已删除' }, {})
    expect(d.action).toBe('toast')
    expect(d.message).toBe('文章不存在或已删除')
  })

  it('无码错误（网络层）→ 通用重试文案', () => {
    expect(decideSaveFailure(new Error('Network Error'), {}).message).toContain('重试')
  })
})

describe('articleFlow: 本地草稿兜底决策', () => {
  const key = () => localDraftKey(null)
  it('新建态与编辑态草稿 key 隔离（互不串写）', () => {
    expect(localDraftKey(null)).not.toBe(localDraftKey(42))
    expect(localDraftKey(42)).toBe('blog:draft:42')
  })

  it('无本地草稿 → 用服务器', () => {
    expect(resolveEditorSeed(null, { contentMd: 's' }, '2026-09-12T10:00:00').use).toBe('server')
  })

  it('本地更新于服务器（崩溃恢复场景）→ 用本地', () => {
    const serverTime = Date.now() - 60_000
    const local = { contentMd: 'l', savedAt: Date.now() }
    expect(resolveEditorSeed(local, { contentMd: 's' }, new Date(serverTime).toISOString()).use).toBe('local')
  })

  it('服务器更新（他端已保存）→ 用服务器，本地只留提示价值', () => {
    const local = { contentMd: 'l', savedAt: Date.now() - 120_000 }
    const r = resolveEditorSeed(local, { contentMd: 's' }, new Date().toISOString())
    expect(r.use).toBe('server')
    expect(r.reason).toContain('更新')
  })
})
