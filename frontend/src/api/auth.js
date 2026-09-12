/**
 * 认证接口封装（M2）—— 路径逐条对齐《API接口设计.md》§2。
 *
 * 为什么单独建 auth.js 而不是让页面直接 import request：
 * 1. URL 与请求方法是前后端契约，集中一处，后端改路径时只碰一个文件；
 * 2. request 拦截器已拆信封（成功直接返回 data），这里的返回类型就是"业务载荷"，
 *    调用方无需再 .data.data（可维护性约定见 request.js 头注释）；
 * 3. 单元层可用 vi.mock 替掉 axios 实例，验证"登录页真的调了这个端点"。
 */
import request from './request'

/** 注册：成功返回 { userId }；重名/重邮箱由拦截器 toast（1101/1102）并 reject */
export function register(data) {
  return request.post('/auth/register', data)
}

/** 登录：成功返回 { token, expiresIn, user }，由调用方交给 userStore.applyLogin */
export function login(data) {
  return request.post('/auth/login', data)
}

/** 登出：服务端拉黑当前 jti；前端无论成败都要清本地态（调用方 finally 里做） */
export function logout() {
  return request.post('/auth/logout')
}

/** 滑动续期：data.renewed=false 表示剩余≥30min 无需换发 */
export function refresh() {
  return request.post('/auth/refresh')
}

/** 当前用户信息 + 权限点：刷新页面后用它把 localStorage 里的旧快照校准一次 */
export function fetchMe() {
  return request.get('/auth/me')
}
