/**
 * M15 前端体验收口纯逻辑单测（Vitest，node 环境）：
 * 1. highlightCode —— 详情页代码高亮（M3/M10 两度顺延项清偿）：
 *    别名归一、未知语言转义不炸、XSS 载荷进围栏必须惰化；
 * 2. renderMarkdown 回归 —— 接了 highlight 回调后，三道闸口径不许漂移；
 * 3. highlightKeyword/validateSearchKeyword —— 搜索回显（v-html 消费点安全）；
 * 4. filterArchive —— 归档页本地过滤（空桶剪枝/不改入参）；
 * 5. 响应式断点回归扫描 —— 机械遍历全部 SFC，@media 断点数必须落在白名单内
 *    （"移动端断点系统验证"的可执行形态：不靠人肉抽查，靠测试红线）。
 */
import { describe, it, expect } from 'vitest'
import { readFileSync, readdirSync, statSync, existsSync } from 'node:fs'
import { join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { renderMarkdown, highlightCode, escapeHtml } from '../markdown.js'
import { highlightKeyword, validateSearchKeyword } from '../search.js'
import { groupArchive, filterArchive } from '../archive.js'
import { MOBILE_MAX, ALLOWED_BREAKPOINTS } from '../responsive.js'

describe('highlightCode: 围栏高亮管道（M15）', () => {
  it('已注册语言产出 hljs span 结构', () => {
    const html = highlightCode('const a = 1', 'javascript')
    expect(html).toContain('<pre class="hljs">')
    expect(html).toContain('language-javascript')
    expect(html).toContain('hljs-keyword')       // const 被真实高亮，不是原样透出
  })

  it('别名归一：js→javascript、yml→yaml、vue→xml', () => {
    expect(highlightCode('', 'js')).toContain('language-javascript')
    expect(highlightCode('a: 1', 'yml')).toContain('language-yaml')
    expect(highlightCode('<b/>', 'vue')).toContain('language-xml')
  })

  it('语言声明带尾巴（js,linenums 老写法）取首词不误伤', () => {
    expect(highlightCode('let x = 1', 'js,linenums')).toContain('language-javascript')
  })

  it('未知语言→全量转义纯文本（不 highlightAuto 嗅探：错色比无色差）', () => {
    const html = highlightCode('foo <bar> "q"', 'brainfuck')
    expect(html).not.toContain('hljs-title')
    expect(html).toContain('&lt;bar&gt;')
    expect(html).toContain('&quot;')
    expect(html).toContain('lang-brainfuck')     // lang- 前缀类名保留（样式钩子契约）
  })

  it('无语言围栏→按纯文本转义输出', () => {
    const html = highlightCode('plain <tag>', '')
    expect(html).toContain('&lt;tag&gt;')
    expect(html).not.toContain('language-')
  })

  it('XSS 载荷进围栏必须惰化：不出现活标签', () => {
    const html = highlightCode('<script>alert(1)</' + 'script>', 'html')
    expect(html).not.toMatch(/<script/i)
    // hljs 会把 < 与 > 分别包进 span（&lt; 出现在高亮 token 里），
    // 所以断言"每个尖括号都以实体形态存在"而不是"存在连续的 &lt;script&gt;"
    expect(html).toContain('&lt;')
    expect(html).toContain('&gt;')
    expect(html).not.toContain('<script>')
  })

  it('截断的坏代码不抛异常（ignoreIllegals 防线）', () => {
    expect(() => highlightCode('def f(:', 'python')).not.toThrow()
  })
})

describe('renderMarkdown: 接高亮后三道闸口径不漂移（回归锁）', () => {
  it('围栏代码块经 highlight 管道：产出 pre.hljs 且载荷惰化', () => {
    const html = renderMarkdown('```html\n<script>x</script>\n```')
    expect(html).toContain('<pre class="hljs">')
    expect(html).not.toMatch(/<script[^&]/i)      // 活的 <script 元素绝不允许
  })

  it('行内代码与普通文本行为不变', () => {
    expect(renderMarkdown('`a < b`')).toContain('<code>')
    expect(renderMarkdown('# 标题')).toContain('<h1>标题</h1>')
  })

  it('原始 HTML 仍被 html:false 挡在门外（高亮回调不改变正文解析）', () => {
    const html = renderMarkdown('<img src=x onerror=alert(1)>')
    expect(html).not.toMatch(/<img[^>]*onerror/i)
  })
})

describe('escapeHtml: 全量实体转义口径', () => {
  it('五种字符全覆盖（& < > " \'）', () => {
    expect(escapeHtml(`&<>"'`)).toBe('&amp;&lt;&gt;&quot;&#39;')
  })
})

describe('highlightKeyword: 搜索回显（v-html 消费点安全红线）', () => {
  it('命中段包 <mark>，忽略大小写', () => {
    expect(highlightKeyword('Spring Boot in Action', 'boot'))
      .toBe('Spring <mark>Boot</mark> in Action')
  })

  it('多处命中全标', () => {
    const r = highlightKeyword('java 与 javaScript', 'java')
    expect((r.match(/<mark>/g) || []).length).toBe(2)
  })

  it('关键词含正则元字符不炸（用户输入 c++ 是常态）', () => {
    expect(() => highlightKeyword('learn c++ today', 'c++')).not.toThrow()
    expect(highlightKeyword('learn c++ today', 'c++')).toContain('<mark>c++</mark>')
  })

  it('文本侧 XSS 载荷惰化：keyword 正常时标题里的 <script> 不复活', () => {
    const r = highlightKeyword('<script>alert(1)</script> 未命中', 'zzz')
    expect(r).not.toMatch(/<script/i)
  })

  it('关键词侧同样惰化：搜 <script 不产出活标签', () => {
    const r = highlightKeyword('a <script> b', '<script>')
    expect(r).toContain('<mark>&lt;script&gt;</mark>')
    expect(r).not.toMatch(/<script[^&]/i)
  })

  it('空关键词/空文本：原样转义返回，不产出 <mark>', () => {
    expect(highlightKeyword('abc', '')).toBe('abc')
    expect(highlightKeyword('', 'abc')).toBe('')
    expect(highlightKeyword(null, null)).toBe('')
  })

  it('validateSearchKeyword：空过、超长拦', () => {
    expect(validateSearchKeyword('').ok).toBe(true)
    expect(validateSearchKeyword('spring').ok).toBe(true)
    const bad = validateSearchKeyword('字'.repeat(51))
    expect(bad.ok).toBe(false)
    expect(bad.message).toContain('50')
  })
})

describe('filterArchive: 归档页本地过滤', () => {
  const tree = {
    2026: { '09': [{ id: 1, title: 'Spring Boot 实战' }, { id: 2, title: 'Redis 缓存' }],
            '08': [{ id: 3, title: 'Vue3 上手' }] },
    2025: { '12': [{ id: 4, title: 'MySQL 索引' }] }
  }
  it('命中词过滤：空月/空年剪枝，年月次序保持', () => {
    const out = filterArchive(groupArchive(tree), 'boot')
    expect(out).toHaveLength(1)
    expect(out[0].year).toBe('2026')
    expect(out[0].months).toHaveLength(1)
    expect(out[0].months[0].items[0].title).toContain('Spring Boot')
  })

  it('忽略大小写；未命中返回空数组（页面走 el-empty 分支）', () => {
    expect(filterArchive(groupArchive(tree), 'BOOT').length).toBe(1)
    expect(filterArchive(groupArchive(tree), 'zzz')).toEqual([])
  })

  it('空关键词=全量透传（与不显示搜索框时行为一致）', () => {
    const out = filterArchive(groupArchive(tree), '   ')
    expect(out.map((y) => y.total)).toEqual([3, 1])
  })

  it('不改入参（Vue 响应式安全）', () => {
    const sections = groupArchive(tree)
    const snap = JSON.stringify(sections)
    filterArchive(sections, 'boot')
    expect(JSON.stringify(sections)).toBe(snap)
  })

  it('脏输入不炸：null/非数组一律空结果', () => {
    expect(filterArchive(null, 'x')).toEqual([])
    expect(filterArchive('nonsense', 'x')).toEqual([])
  })
})

describe('断点系统回归：全站 SFC @media 扫描（移动端断点验证的机械形态）', () => {
  /** 递归收集 src 下全部 .vue（views + components 都在射程内） */
  function collectVue(dir, acc = []) {
    for (const name of readdirSync(dir)) {
      const p = join(dir, name)
      if (statSync(p).isDirectory()) collectVue(p, acc)
      else if (name.endsWith('.vue')) acc.push(p)
    }
    return acc
  }

  /** src 根目录：fileURLToPath 处理 Windows 盘符与中文路径的百分号编码
   *  （pathname 直取在 D:\毕业设计 这类路径下会给出编码串，readFileSync 找不到） */
  const root = fileURLToPath(new URL('../../', import.meta.url))
  it('每个 @media (max-width: Npx) 的 N 必须 ∈ 白名单（防页面各写各的）', () => {
    expect(existsSync(join(root, 'views'))).toBe(true)
    const files = collectVue(join(root, 'views'))
    expect(files.length).toBeGreaterThan(10)   // 扫描本身没空转
    const offenders = []
    for (const f of files) {
      const css = readFileSync(f, 'utf8')
      for (const m of css.matchAll(/@media[^{]*?\(max-width:\s*(\d+)px\s*\)/g)) {
        if (!ALLOWED_BREAKPOINTS.includes(Number(m[1]))) {
          offenders.push(`${f.split(/[\\/]/).pop()}: ${m[1]}px`)
        }
      }
    }
    expect(offenders, `白名单外断点 ${ALLOWED_BREAKPOINTS.join('/')}px：\n${offenders.join('\n')}`).toEqual([])
  })

  it('公开站核心页必须各自至少有一条移动端折叠规则（系统回归的正向断言，防"扫描通过=一条都没有"的假绿）', () => {
    const must = ['SiteLayout.vue', 'HomeView.vue', 'ArticleDetailView.vue', 'ArchiveView.vue', 'LoginView.vue', 'ArticleEditView.vue']
    for (const f of must) {
      const css = readFileSync(join(root, 'views', f), 'utf8')
      expect(css, `${f} 缺移动端 @media 规则`).toContain(`max-width: ${MOBILE_MAX}px`)
    }
  })
})
