/**
 * Markdown 安全渲染管道（M3）—— 《系统设计.md》§3.2 "前端 markdown-it + DOMPurify
 * 二次渲染"的实现，NFR-05 双防线的前端半边。
 *
 * 三道闸（每道都在防不同的东西）：
 *  1. markdown-it html:false —— 正文里的原始 HTML 标签（XSS 载荷主形态）被转义成
 *     纯文本节点，从源头不产出元素。服务端 content_md 按"所见即所存"原文入库
 *     （不替用户改内容），所以防线必须落在渲染层。
 *  2. validateLink 协议白名单 —— markdown-it 内置已禁 javascript:/vbscript:/data:text/html，
 *     这里再收紧为 http/https/mailto/相对路径显式白名单：黑名单永远漏，白名单永远稳。
 *     典型事故：[点我](javascript:alert(1)) 在旧渲染器里是可点的一等公民。
 *  3. DOMPurify.sanitize —— 兜底清洗最终 HTML（防御 markdown-it 插件链绕过与
 *     浏览器解析差异，D-7 预研结论：客户端单防线不可信，必须叠通用净化器）。
 *
 * 可测性设计：node 环境（Vitest，无 DOM）下 DOMPurify.isSupported=false，
 * 此时跳过第 3 道并原样返回——第 1、2 道是 markdown-it 的纯 JS 行为，
 * node 里完全可断言；DOMPurify 真实生效由浏览器端（M10 手工回归）验证。
 * 这样分层是为了"纯逻辑进单测、DOM 依赖不背 jsdom 安装成本"（沿用 vite.config.js
 * 既定口径：environment:node）。
 */
import MarkdownIt from 'markdown-it'
import DOMPurify from 'dompurify'

/** 渲染器单例：全局共享一份配置，避免每篇文章 new 一次的开销与配置漂移 */
const md = new MarkdownIt({
  html: false,        // 闸 1：不解析正文内嵌 HTML
  linkify: true,      // 裸 URL 自动成链（博客正文常见诉求）
  breaks: true,       // 单换行即 <br>：匹配中文用户的写作直觉（CommonMark 默认要两空格才换行）
  langPrefix: 'lang-', // 代码块语言类名前缀：交给 highlight.js/低亮样式钩子
  /**
   * 闸 2：链接协议白名单。覆盖 markdown-it 默认实现（默认已是黑名单式，
   * 但 linkify 生成的 href 同样过这里，一处收口所有链接来源）。
   * 相对路径（/uploads/x.png、#锚点、./doc）放行——博客内链与图片都是相对形态。
   */
  validateLink(url) {
    try {
      const u = String(url).trim()
      if (/^(\/|#|\.\/|\.\.\/)/.test(u)) return true          // 站内相对/锚点
      // node(Vitest) 无 window：typeof 防护而非 window?.（未声明标识符连 ?. 都会 ReferenceError）
      const base = typeof window !== 'undefined' && window.location
        ? window.location.origin : 'http://localhost'
      const proto = new URL(u, base).protocol
      return ['http:', 'https:', 'mailto:'].includes(proto)   // 显式白名单：data:/javascript:/blob: 全灭
    } catch {
      return false                                            // 解析不了的 URL 一律不可信
    }
  }
})

/**
 * 渲染并净化：Markdown 原文 → 安全 HTML 字符串。
 * @param {string} src content_md
 * @returns {string} 可直接 v-html 的 HTML
 */
export function renderMarkdown(src) {
  const html = md.render(src || '')
  // 闸 3：浏览器环境必过 DOMPurify；node 测试环境跳过（见头注释的分层理由）
  if (DOMPurify.isSupported) {
    return DOMPurify.sanitize(html, {
      USE_PROFILES: { html: true },          // 允许常规排版标签，禁 SVG/MathML（历史绕过重灾区）
      FORBID_TAGS: ['style', 'form'],        // 内联样式表可被用来做 CSS 注入/点击劫持坐标操纵
      ADD_ATTR: ['target'],                  // 外链在新标签打开是正文刚需；配 ALLOWED_ATTR 白名单不放开 on*
    })
  }
  return html
}

/**
 * 从渲染结果提取目录（TOC）——详情页右侧导航（UC-V-02 要求"目录 TOC"）。
 *
 * 为什么在 HTML 字符串上用正则扫 <h2>/<h3> 而不是 DOM 遍历：
 * node 可测（纯字符串）、无 jsdom 依赖、且标题层级在本博客约定里就是 h2/h3
 * （h1 留给文章标题，编辑器模板里已经约束）；DOM 版本留给 M10 若需滚动高亮再加。
 *
 * @param {string} html renderMarkdown 的输出
 * @returns {Array<{level:number, id:string, text:string}>}
 */
export function extractToc(html) {
  const toc = []
  const re = /<h([23])[^>]*>([\s\S]*?)<\/h\1>/g
  let m
  let n = 0
  while ((m = re.exec(html)) !== null) {
    // 标签内可能残留行内标记（<strong> 等），剥成纯文本再入库/展示
    const text = m[2].replace(/<[^>]+>/g, '').trim()
    if (!text) continue
    const id = `toc-${n++}`   // 生成稳定锚点：不依赖中文 slug 化（分词策略争议不值得在这里花预算）
    toc.push({ level: Number(m[1]), id, text })
  }
  return toc
}

/**
 * 给 TOC 对应的标题补 id 属性（extractToc 与真实渲染 DOM 的锚点联接线）。
 * 按出现次序一一对应写回，避免二次解析。
 */
export function injectTocIds(html, toc) {
  let i = 0
  return html.replace(/<h([23])(\s[^>]*)?>/g, (tag, level, attrs) => {
    const item = toc[i++]
    return item ? `<h${level} id="${item.id}"${attrs || ''}>` : tag
  })
}
