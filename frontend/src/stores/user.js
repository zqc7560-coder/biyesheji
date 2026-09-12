/**
 * 用户状态 store（Pinia）—— 登录态的单一事实来源。
 *
 * 设计：token 真相在 localStorage（刷新不丢），store 里的 user/roles 是内存快照
 * （由登录响应或 /api/auth/me 填充）。路由守卫只问 store 的 isLoggedIn，
 * 初始化时从 localStorage 水合一次——避免"守卫查 localStorage、组件查 store"
 * 两套状态漂移的经典 bug。
 */
import { defineStore } from 'pinia'
import { getToken, setToken, clearToken, getCachedUser, setCachedUser } from '@/utils/auth'

export const useUserStore = defineStore('user', {
  state: () => ({
    token: getToken(),
    user: getCachedUser(),
    // 权限点数组（登录响应 user.perms 或 me 接口带回），M2 起驱动后台菜单过滤
    perms: getCachedUser()?.perms || []
  }),

  getters: {
    isLoggedIn: (s) => Boolean(s.token),
    isAdmin: (s) => (s.user?.roles || []).includes('ROLE_ADMIN')
  },

  actions: {
    /** 登录成功后落盘：token 进 localStorage，用户快照（含 roles/perms）同步 */
    applyLogin(token, user) {
      this.token = token
      this.user = user
      this.perms = user?.perms || []
      setToken(token)
      setCachedUser(user)
    },

    /** 登出：清内存 + 清存储（服务端拉黑 jti 由调用方先完成） */
    logout() {
      this.token = ''
      this.user = null
      this.perms = []
      clearToken()
    }
  }
})
