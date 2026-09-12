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
    component: () => import('@/views/UserCenterView.vue'),
    meta: { requiresAuth: true },
    children: [
      // M3：用户中心从"单页占位"升级为带子路由的壳（我的文章/编辑器挂进来，资料设置 M2 能力保持原样并入 index）
      { path: '', name: 'UserCenter', component: () => import('@/views/UserHomeView.vue'), meta: { title: '用户中心' } },
      { path: 'articles', name: 'MyArticles', component: () => import('@/views/MyArticleListView.vue'), meta: { title: '我的文章', requiresAuth: true } },
      // 编辑器：new 与 :id/edit 复用同一组件（组件内按 route.params.id 区分新建/编辑态）
      { path: 'articles/new', name: 'ArticleNew', component: () => import('@/views/ArticleEditView.vue'), meta: { title: '写文章', requiresAuth: true } },
      { path: 'articles/:id/edit', name: 'ArticleEdit', component: () => import('@/views/ArticleEditView.vue'), meta: { title: '编辑文章', requiresAuth: true } },
      // M5：我的评论（UC-U-12 列表部分；含待审/被屏蔽态，对自己透明）
      { path: 'comments', name: 'MyComments', component: () => import('@/views/MyCommentListView.vue'), meta: { title: '我的评论', requiresAuth: true } }
    ]
  },
  {
    // 文章详情：公开路由（游客可访问，可见性由后端矩阵裁决）；独立于 /user 壳避免布局约束
    path: '/articles/:id',
    name: 'ArticleDetail',
    component: () => import('@/views/ArticleDetailView.vue'),
    meta: { title: '文章详情' }
  },
  {
    // M2 后台布局框架：AdminLayout 侧边菜单按权限点过滤渲染（阶段三任务 6），
    // 子页面经 children 挂进 <router-view/>；整棵树仍受 ADMIN 角色守卫保护。
    path: '/admin',
    component: () => import('@/views/AdminLayout.vue'),
    meta: { title: '管理后台', requiresAuth: true, roles: ['ROLE_ADMIN'] },
    children: [
      { path: '', name: 'AdminDashboard', component: () => import('@/views/admin/AdminDashboardView.vue'), meta: { title: '仪表盘 - 管理后台' } },
      { path: 'users', name: 'AdminUsers', component: () => import('@/views/admin/AdminUserView.vue'), meta: { title: '用户管理 - 管理后台' } },
      // M3：文章治理页（UC-A-02），menu.js 里声明的 articles 入口在此落地
      { path: 'articles', name: 'AdminArticles', component: () => import('@/views/admin/AdminArticleView.vue'), meta: { title: '文章管理 - 管理后台' } },
      // M4：分类/标签治理页（UC-A-03/04），menu.js 声明的 categories/tags 入口落地
      { path: 'categories', name: 'AdminCategories', component: () => import('@/views/admin/AdminCategoryView.vue'), meta: { title: '分类管理 - 管理后台' } },
      { path: 'tags', name: 'AdminTags', component: () => import('@/views/admin/AdminTagView.vue'), meta: { title: '标签管理 - 管理后台' } },
      // M5：评论审核页（UC-A-05），menu.js 声明的 comments 入口落地
      { path: 'comments', name: 'AdminComments', component: () => import('@/views/admin/AdminCommentView.vue'), meta: { title: '评论审核 - 管理后台' } }
    ]
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
