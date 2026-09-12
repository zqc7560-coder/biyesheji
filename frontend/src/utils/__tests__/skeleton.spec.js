/**
 * 前端骨架逻辑单测（Vitest）—— 覆盖本轮可测的两块纯逻辑：
 * 1. resolveEnvelope：Axios 拦截器的统一响应体拆解契约（与后端 Result 结构对齐）；
 * 2. useUserStore：登录态水合/落盘/清空（localStorage 用轻量 stub 模拟 node 环境）。
 *
 * 为什么测这两个：它们是 M2 之后全站网络层与权限的承重墙——信封拆错、
 * 认证失效不登出，症状会漂移到几十个页面里，必须在这里先锁死。
 */
import { describe, it, expect, beforeEach } from 'vitest'
import { resolveEnvelope, AUTH_DEAD_CODES } from '../../api/request.js'

// node 环境没有 localStorage：用 Map stub 顶一下，被测代码的 try/catch 不触发
const store = new Map()
globalThis.localStorage = {
  getItem: (k) => (store.has(k) ? store.get(k) : null),
  setItem: (k, v) => store.set(k, String(v)),
  removeItem: (k) => store.delete(k)
}

describe('resolveEnvelope: 统一响应体拆解', () => {
  it('code=0 时返回 ok 并剥离出 data 载荷', () => {
    const r = resolveEnvelope({ code: 0, message: 'success', data: { id: 42 }, timestamp: 1 })

    expect(r.ok).toBe(true)
    expect(r.payload).toEqual({ id: 42 })
    expect(r.authDead).toBeFalsy()
  })

  it('code=0 且 data=null（如登出）也判成功：payload 允许为空', () => {
    const r = resolveEnvelope({ code: 0, message: 'success', data: null, timestamp: 1 })

    expect(r.ok).toBe(true)
    expect(r.payload).toBeNull()
  })

  it.each([1001, 1004])('认证失效码 %i：authDead=true 触发清令牌跳登录', (code) => {
    expect(AUTH_DEAD_CODES).toContain(code)
    const r = resolveEnvelope({ code, message: '未认证或令牌失效', data: null })

    expect(r.ok).toBe(false)
    expect(r.authDead).toBe(true)
  })

  it('1002 密码错误不算认证失效：停在登录页展示文案，不能跳走', () => {
    const r = resolveEnvelope({ code: 1002, message: '用户名或密码错误', data: null })

    expect(r.ok).toBe(false)
    expect(r.authDead).toBe(false)
    expect(r.message).toBe('用户名或密码错误')
  })

  it('业务错误码原样透出 message（服务端已本地化，前端不二次翻译）', () => {
    const r = resolveEnvelope({ code: 2004, message: '内容已被他人更新，请刷新后重试' })

    expect(r.ok).toBe(false)
    expect(r.message).toContain('刷新')
  })

  it('非信封响应（网关 HTML 错误页等）判失败且不误触发登出', () => {
    expect(resolveEnvelope('<html>502</html>').ok).toBe(false)
    expect(resolveEnvelope('<html>502</html>').authDead).toBe(false)
    expect(resolveEnvelope(null).ok).toBe(false)
  })
})

describe('auth.js: 令牌持久化', () => {
  let auth
  beforeEach(async () => {
    store.clear()
    auth = await import('../auth.js')
  })

  it('未写入时 getToken 返回空串（truthy 判断可直接当登录态）', () => {
    expect(auth.getToken()).toBe('')
  })

  it('setToken → getToken 往返一致', () => {
    auth.setToken('abc.def.ghi')
    expect(auth.getToken()).toBe('abc.def.ghi')
  })

  it('clearToken 同时清令牌与用户缓存', () => {
    auth.setToken('t')
    auth.setCachedUser({ id: 1, roles: ['ROLE_ADMIN'] })
    auth.clearToken()

    expect(auth.getToken()).toBe('')
    expect(auth.getCachedUser()).toBeNull()
  })

  it('用户缓存 JSON 往返：roles/perms 结构不失真', () => {
    const user = { id: 1, username: 'admin', roles: ['ROLE_ADMIN'], perms: ['ai:config'] }
    auth.setCachedUser(user)

    expect(auth.getCachedUser()).toEqual(user)
  })
})

describe('user store: 登录态单一事实来源', () => {
  let useUserStore, setActivePinia, createPinia
  beforeEach(async () => {
    store.clear()
    ;({ setActivePinia, createPinia } = await import('pinia'))
    ;({ useUserStore } = await import('../../stores/user.js'))
    setActivePinia(createPinia())
  })

  it('初始水合：localStorage 有令牌则 store 直接 isLoggedIn（刷新页面免重登）', () => {
    localStorage.setItem('blog_token', 'saved.jwt')

    const fresh = useUserStore()
    // store 在 defineStore 时才读 initial state，重新建 pinia 模拟刷新
    setActivePinia(createPinia())
    const afterReload = useUserStore()

    expect(afterReload.isLoggedIn).toBe(true)
    expect(fresh).toBeTruthy()
  })

  it('applyLogin 双写：内存态 + localStorage 落盘', () => {
    const s = useUserStore()
    s.applyLogin('jwt.token.here', { id: 2, username: 'u', roles: ['ROLE_USER'], perms: ['ai:use'] })

    expect(s.isLoggedIn).toBe(true)
    expect(localStorage.getItem('blog_token')).toBe('jwt.token.here')
    expect(s.isAdmin).toBe(false)
    expect(s.perms).toEqual(['ai:use'])
  })

  it('isAdmin 依据 roles 判定（后台菜单过滤的输入）', () => {
    const s = useUserStore()
    s.applyLogin('t', { id: 1, roles: ['ROLE_ADMIN'], perms: [] })

    expect(s.isAdmin).toBe(true)
  })

  it('logout 彻底清场：内存与存储都不残留', () => {
    const s = useUserStore()
    s.applyLogin('t', { id: 1, roles: ['ROLE_USER'], perms: ['x'] })
    s.logout()

    expect(s.isLoggedIn).toBe(false)
    expect(s.user).toBeNull()
    expect(s.perms).toEqual([])
    expect(localStorage.getItem('blog_token')).toBeNull()
    expect(localStorage.getItem('blog_user')).toBeNull()
  })
})
