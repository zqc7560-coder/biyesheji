/**
 * 路由表（M0 骨架版）：公开站 / 登录 / 用户中心 / 管理后台 四棵路由树的占位。
 *
 * 路由守卫规则（《项目计划.md》阶段二）：
 * - meta.requiresAuth 的路由未登录跳 /login?redirect=原地址；
 * - meta.roles 含 ADMIN 的路由要求 isAdmin，否则回首页（越权三防的前端一层，
 *   真正的防线在后端 @PreAuthorize，前端守卫只是体验优化）。
 */
import { createRouter, createWebHistory } from 'vue-router'
import { useUserStore } from '@/stores/user'

const routes = [
  {
    path: '/',
    name: 'Home',
    component: () => import('@/views/HomeView.vue'),
    meta: { title: '首页' }
  },
  {
    path: '/login',
    name: 'Login',
    component: () => import('@/views/LoginView.vue'),
    meta: { title: '登录', guestOnly: true }
  },
  {
    path: '/user',
    name: 'UserCenter',
    component: () => import('@/views/UserCenterView.vue'),
    meta: { title: '用户中心', requiresAuth: true }
  },
  {
    path: '/admin',
    name: 'Admin',
    component: () => import('@/views/AdminView.vue'),
    meta: { title: '管理后台', requiresAuth: true, roles: ['ROLE_ADMIN'] }
  }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

router.beforeEach((to) => {
  const store = useUserStore()
  document.title = `${to.meta.title || ''} - 个人博客系统`

  if (to.meta.requiresAuth && !store.isLoggedIn) {
    return { path: '/login', query: { redirect: to.fullPath } }
  }
  if (to.meta.roles && !to.meta.roles.some((r) => (store.user?.roles || []).includes(r))) {
    return { path: '/' }
  }
  // 已登录用户不再看登录/注册页
  if (to.meta.guestOnly && store.isLoggedIn) {
    return { path: '/' }
  }
  return true
})

export default router
