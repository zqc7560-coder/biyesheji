/**
 * 令牌持久化工具：localStorage 的薄封装（键名集中在下面两个常量）。
 *
 * 为什么单独抽出来而不是散在 axios/router 里：
 * 1. 下面两个键名是"前端存 JWT"的单一事实来源，登出清理、拦截器注入、
 *    路由守卫三处都读它们——一处改名三处生效；
 * 2. localStorage 在隐私模式/配额满时会抛异常，统一 try/catch 兜底，
 *    避免"存取令牌"这种基础动作把页面搞白屏；
 * 3. 可测性：这个纯函数模块是前端本轮 Vitest 的被测对象。
 *
 * 安全说明：JWT 存 localStorage 有 XSS 窃取面，但后端已有 XSS 双防线
 * （jsoup 服务端清洗 + DOMPurify 渲染消毒，《系统设计.md》D-7），且登出走
 * 服务端 jti 黑名单，令牌被盗也有 TTL 2h 上限——权衡后采用业界通行的
 * localStorage 方案（毕设体量下 httpOnly Cookie + CSRF 的组合成本更高）。
 */

/** localStorage 键名：令牌 */
const TOKEN_KEY = 'blog_token'
/** localStorage 键名：登录用户信息缓存 */
const USER_KEY = 'blog_user'

/** 读取令牌；无存储或异常时返回空串（调用方用 truthy 判断即可） */
export function getToken() {
  try {
    return localStorage.getItem(TOKEN_KEY) || ''
  } catch {
    return ''
  }
}

/** 写入令牌（登录成功后调用） */
export function setToken(token) {
  try {
    localStorage.setItem(TOKEN_KEY, token)
  } catch {
    /* 隐私模式写入失败：降级为内存态（store 里仍留有 token） */
  }
}

/** 清除令牌与用户缓存（登出/401 时调用） */
export function clearToken() {
  try {
    localStorage.removeItem(TOKEN_KEY)
    localStorage.removeItem(USER_KEY)
  } catch {
    /* 同上，静默 */
  }
}

/** 缓存登录用户信息（昵称/角色），刷新页面时免一次 /api/auth/me 往返 */
export function setCachedUser(user) {
  try {
    localStorage.setItem(USER_KEY, JSON.stringify(user))
  } catch { /* 静默 */ }
}

/** 读取缓存用户信息；解析失败视为无缓存 */
export function getCachedUser() {
  try {
    const raw = localStorage.getItem(USER_KEY)
    return raw ? JSON.parse(raw) : null
  } catch {
    return null
  }
}
