/**
 * 搜索体验纯函数（M15 前端体验收口）——首页列表关键词命中的可视化回显。
 *
 * 为什么关键词高亮放在前端而不是后端返 HTML：
 * ① 后端 GET /api/articles 的 keyword 走 MySQL ngram 全文索引（数据库设计 §4），
 *    服务端只回纯文本字段——若回高亮 HTML，等于把"标题里的用户输入"重新变成
 *    渲染载荷，DOMPurify 白名单外还要为 <mark> 开口子，安全面反而扩大；
 * ② 前端拿 title/summary + 用户自己的 keyword 做纯字符串切分，输入两侧都可控，
 *    是更窄的信任边界。
 *
 * 安全红线（与 utils/markdown.js 三道闸同源）：产出物会被 v-html 消费，
 * 所以文本段与关键词段都必须先过 escapeHtml，本函数只注入 <mark> 这一种标签。
 */
import { escapeHtml } from './markdown.js'

/**
 * 关键词高亮：把 text 中出现的 keyword（忽略大小写）包进 <mark>。
 *
 * 决策说明：
 * · 多关键词（"spring boot"）按整串匹配而不是拆词——MySQL ngram 命中逻辑与
 *   "整串出现"不完全等价，但拆词高亮会造成"标题里每个词都花"的噪声；
 *   整串高亮哪怕少标几处也不会标错，是"宁缺勿滥"的取舍。
 * · 空关键词/无命中原样转义返回：调用方（列表卡片）无条件 v-html，
 *   所以"无高亮"的分支也必须返回安全串，不能偷懒返回原文。
 * · 正则用 i 标志且手动转义关键词里的元字符（escapeRegExp）——用户输入
 *   "c++" 不转义会直接抛 SyntaxError 把整个列表渲染掀翻。
 *
 * @param {string} text 原始文本（用户可控）
 * @param {string} keyword 搜索词（用户可控）
 * @returns {string} 仅含 <mark> 的安全 HTML
 */
export function highlightKeyword(text, keyword) {
  const src = String(text ?? '')
  const kw = String(keyword ?? '').trim()
  if (!src) return ''
  if (!kw) return escapeHtml(src)
  const re = new RegExp(escapeRegExp(kw).replace(/\s+/g, '\\s+'), 'gi')
  let out = ''
  let last = 0
  let m
  while ((m = re.exec(src)) !== null) {
    if (m.index > last) out += escapeHtml(src.slice(last, m.index))
    out += `<mark>${escapeHtml(m[0])}</mark>`
    last = m.index + m[0].length
    // 零宽匹配防御：某些正则引擎下空匹配会让 exec 原地踏步死循环
    if (m[0].length === 0) re.lastIndex++
  }
  out += escapeHtml(src.slice(last))
  return out
}

/** 正则元字符转义（keyword 里出现 ( [ . * 等不炸正则） */
function escapeRegExp(s) {
  return s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
}

/**
 * 搜索词合法性前置检查：ngram 全文索引对 1 字符查询命中质量极差
 * （ngram_token_size=2，单字走不了索引会退化全表），后端 DTO 也有 min 长度语义。
 * 前端在发请求前挡一下，比"转一圈回来列表空一片、用户以为没内容"体验好。
 * @returns {null | {ok:true} | {ok:false, message:string}}
 */
export function validateSearchKeyword(kw) {
  const v = String(kw ?? '').trim()
  if (!v) return { ok: true }                        // 空=清除搜索，合法
  if (v.length > 50) return { ok: false, message: '搜索词请控制在 50 字以内' }
  return { ok: true }
}
