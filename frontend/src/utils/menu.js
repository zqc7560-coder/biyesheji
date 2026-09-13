/**
 * 后台菜单定义与权限过滤（M2，项目计划阶段三任务 6：
 * "后台布局框架（侧边菜单按权限点过滤渲染）"）。
 *
 * 设计要点：
 * 1. 菜单清单集中声明为纯数据（label/path/icon/perm），渲染层只做 map——
 *    新增后台页面改这一个数组，不动布局组件；
 * 2. 过滤依据是"权限点"而不是角色码：与后端 @PreAuthorize("hasAuthority(...)")
 *    同一把尺子（《API接口设计.md》§8.3 越权三防第二层的前端投影）。
 *    前端过滤只是体验优化（不展示进不去的入口），真防线永远在服务端；
 * 3. perm 为 null 的项人人可见（如"个人设置"）；
 * 4. 纯函数 + 纯数据，不 import vue/pinia——所以能在 node 环境的 Vitest 里
 *    直接断言，不用为一条过滤规则背 jsdom 的安装成本（环境约束：依赖最小化）。
 */

/** 后台菜单清单（path 相对 /admin；perm 引用后端 sys_permission.perm_code 种子值） */
export const ADMIN_MENUS = [
  { key: 'dashboard', label: '仪表盘', path: '', perm: null },                       // /admin
  { key: 'articles', label: '文章管理', path: 'articles', perm: 'article:save' },     // M3 落地页面
  { key: 'trash', label: '回收站', path: 'trash', perm: 'article:save' },             // M10（UC-U-09 彻底删除通道；与文章管理同权限族，后端三口 hasRole('ADMIN')）
  { key: 'categories', label: '分类管理', path: 'categories', perm: 'category:manage' }, // M4
  { key: 'tags', label: '标签管理', path: 'tags', perm: 'tag:manage' },               // M4
  { key: 'comments', label: '评论审核', path: 'comments', perm: 'comment:audit' },    // M5
  { key: 'users', label: '用户管理', path: 'users', perm: 'user:manage' },            // M2 接口已就绪
  { key: 'ai-config', label: 'AI 配置', path: 'ai/config', perm: 'ai:config' },       // M6（课题重点）
  { key: 'ai-logs', label: 'AI 调用日志', path: 'ai/logs', perm: 'ai:log' },          // M6
  { key: 'ai-stats', label: 'AI 成本统计', path: 'ai/stats', perm: 'ai:log' },         // M7（与日志同"读账本"权限族）
  { key: 'settings', label: '系统设置', path: 'settings', perm: 'config:manage' }     // M8
]

/**
 * 按权限点集合过滤菜单。
 * @param {string[]} perms 当前用户权限点（来自登录响应 / /api/auth/me 的 user.perms）
 * @returns {Array} 该用户可见的菜单项（保持声明顺序 = 业务优先级顺序）
 */
export function filterMenus(perms) {
  const owned = Array.isArray(perms) ? perms : []   // null/undefined 防御：视为无权限点
  return ADMIN_MENUS.filter((m) => m.perm === null || owned.includes(m.perm))
}
