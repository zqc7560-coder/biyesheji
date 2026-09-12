/**
 * Axios 封装 —— 《项目计划.md》阶段二验收项："拦截器：401→登录页、message 统一 toast"。
 *
 * 请求拦截器：注入 Authorization: Bearer（JWT 无状态认证的客户端侧）。
 * 响应拦截器做三层收敛：
 *   1) HTTP 层错误（网络断/5xx 裸奔）→ toast 网络异常；
 *   2) 业务信封 code≠0 → 按错误码区段动作：
 *      1001/1004 = 认证失效 → 清令牌 + 跳登录（防"僵尸页面"反复 401）；
 *      其余 = toast 后端中文文案（服务端已本地化，前端不再维护第二套文案）；
 *   3) 成功 → 直接返回 data 载荷，业务代码不再解三层信封（.data.data 满天飞是
 *      可维护性毒药）。
 *
 * 单独可测：把"信封拆解 + 认证失效判定"抽成纯函数 interceptorBehaviors，
 * Vitest 无需 mock 整个 axios/路由即可锁死契约逻辑。
 */
import axios from 'axios'
import { ElMessage } from 'element-plus'
import { getToken, clearToken } from '@/utils/auth'

/** 认证失效码（1001 未认证/黑名单、1004 过期）：仅此二码触发"清令牌跳登录" */
export const AUTH_DEAD_CODES = [1001, 1004]

/**
 * 纯函数：对响应体判定动作。拆出来是为了单测不依赖浏览器环境。
 * @param {{code:number, message:string, data:*}} body 统一响应体
 * @returns {{ok:boolean, payload?:*, authDead?:boolean}}
 */
export function resolveEnvelope(body) {
  if (body && typeof body === 'object' && 'code' in body) {
    if (body.code === 0) {
      return { ok: true, payload: body.data }
    }
    return {
      ok: false,
      authDead: AUTH_DEAD_CODES.includes(body.code),
      message: body.message
    }
  }
  // 非信封响应（如后端未就绪返回的原始文本）：视为失败但不跳登录
  return { ok: false, authDead: false, message: '响应格式异常' }
}

/** 跳转登录页：用 location 硬跳，避免 api 层反向 import router 造成循环依赖 */
function redirectToLogin() {
  clearToken()
  if (typeof window !== 'undefined' && !window.location.pathname.startsWith('/login')) {
    window.location.href = '/login'
  }
}

const service = axios.create({
  baseURL: '/api',   // dev 走 Vite 代理、prod 走 Nginx 反代，值恒定（见 vite.config.js 注释）
  timeout: 40000     // 必须 > AI 调用链 35s 超时（《部署与测试.md》§1.3 Nginx 60s 对齐逻辑）
})

service.interceptors.request.use((config) => {
  const token = getToken()
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

service.interceptors.response.use(
  (response) => {
    const r = resolveEnvelope(response.data)
    if (r.ok) return r.payload
    if (r.authDead) redirectToLogin()
    ElMessage.error(r.message || '请求失败')
    return Promise.reject(new Error(r.message))
  },
  (error) => {
    // HTTP 层错误也优先尝试读信封（401/403/429 时后端仍返回统一结构）
    const body = error.response?.data
    const r = body ? resolveEnvelope(body) : null
    if (r?.authDead) redirectToLogin()
    ElMessage.error(r && !r.ok ? r.message : '网络异常，请稍后重试')
    return Promise.reject(error)
  }
)

export default service
