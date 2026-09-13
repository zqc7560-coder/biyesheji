/**
 * 后台菜单权限过滤单测（M2）—— 锁死"菜单按权限点渲染"的输入输出契约。
 * 它是《项目计划》阶段三任务 6 的核心可测逻辑：给什么 perms 出什么菜单，
 * 顺序稳定（业务优先级）、无权限点集合安全降级为空菜单（只剩人人可见项）。
 */
import { describe, it, expect } from 'vitest'
import { ADMIN_MENUS, filterMenus } from '../menu.js'

const labels = (perms) => filterMenus(perms).map((m) => m.label)

describe('filterMenus: 侧边菜单按权限点过滤', () => {
  it('管理员权限点全集：10 个菜单全部渲染且保持声明顺序（M10 回收站入列）', () => {
    const adminPerms = ['article:save', 'article:delete', 'ai:use', 'ai:config', 'ai:log',
      'user:manage', 'comment:audit', 'category:manage', 'tag:manage', 'config:manage']
    expect(labels(adminPerms)).toEqual(ADMIN_MENUS.map((m) => m.label))
  })

  it('普通用户权限点：只剩仪表盘、文章管理与回收站（其余治理入口全部隐藏）', () => {
    // 文章管理/回收站都挂 article:save（种子无 article:manage 治理码，10 权限点清单不许擅改）。
    // 误入风险为零：/admin 整棵路由树有 ROLE_ADMIN 守卫，普通用户根本进不来，
    // 这条过滤只在"未来中间角色"场景生效；后端接口才是真防线。
    expect(labels(['article:save', 'article:delete', 'ai:use'])).toEqual(['仪表盘', '文章管理', '回收站'])
  })

  it('部分权限：只露出对应入口（中间角色演进场景，如只给 comment:audit）', () => {
    expect(labels(['comment:audit'])).toEqual(['仪表盘', '评论审核'])
  })

  it('脏输入安全降级：null/undefined/非数组 都只剩人人可见项，不抛异常', () => {
    for (const bad of [null, undefined, 'user:manage', 42]) {
      expect(labels(bad)).toEqual(['仪表盘'])
    }
  })

  it('菜单 perm 值必须是后端权限点种子的子集（防手滑写出库里不存在的码）', () => {
    const SEED_PERMS = ['article:save', 'article:delete', 'ai:use', 'ai:config', 'ai:log',
      'user:manage', 'comment:audit', 'category:manage', 'tag:manage', 'config:manage']
    for (const m of ADMIN_MENUS) {
      if (m.perm !== null) expect(SEED_PERMS).toContain(m.perm)
    }
  })
})
