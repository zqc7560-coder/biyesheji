/**
 * 响应式断点单一事实源（M15 移动端断点系统回归）。
 *
 * 背景（台账 M4/M10）：M4 只在 HomeView 里手写了一个 `@media (max-width: 768px)`，
 * 其余页面各写各的 max-width 容器，没有系统验证——《部署与测试.md》W13 明确
 * 要求"浏览器兼容与移动端断点抽查"。本文件把断点收口成一处：
 *
 * ① JS 侧（useMediaQuery 场景：编辑器在窄屏降级为只读，见 §部署与测试 手工测试行
 *    "编辑器移动端断点降级为只读"）消费 MOBILE_MAX 常量；
 * ② CSS 侧各组件 @media 统一写 `@media (max-width: 768px)` 字面量——SFC scoped
 *    样式里没有 var() 参与 media query 的能力（CSS 自定义属性进不了 @media 条件），
 *    所以"单一事实源"落地为：本文件是断点数值的权威定义 + m15.spec.js 机械扫描
 *    全部 SFC，出现清单外断点数即测试红。想改断点：改这里 → 跑测试 → 按报错页改。
 */

/** 移动端上界：≤768px 视为手机/小平板（与 Element Plus lg 断点口径一致） */
export const MOBILE_MAX = 768

/**
 * 全站允许出现的 @media 断点集合（px）。白名单外的一律视为"页面各写各的"漂移，
 * 由 m15.spec.js 的样式扫描用例拦截。
 * · 768：公开站单栏化折叠线
 * · 900：后台仪表盘双栏→单栏（M9 既有，收编进白名单）
 * · 480：小屏进一步压缩（详情页/归档页字号与留白）
 */
export const ALLOWED_BREAKPOINTS = [480, 768, 900]

/** 运行时判定（JS 侧唯一入口；SSR/测试环境无 matchMedia 时保守判桌面） */
export function isMobileViewport() {
  if (typeof window === 'undefined' || !window.matchMedia) return false
  return window.matchMedia(`(max-width: ${MOBILE_MAX}px)`).matches
}
