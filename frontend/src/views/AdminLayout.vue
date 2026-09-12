<script setup>
/**
 * 后台布局框架（M2，项目计划阶段三任务 6："后台布局框架——侧边菜单按权限点过滤渲染"）。
 *
 * 结构：el-container = 左侧 el-aside 菜单 + 右侧 header(用户区)/main(router-view)。
 * 子页面（用户管理等）作为 /admin 的 children 渲染在 main 里。
 *
 * 权限口径：菜单显隐用 filterMenus(userStore.perms)（utils/menu.js 纯函数），
 * perms 来自登录响应/持久化快照——这是"体验层"；后端每个接口仍会独立
 * @PreAuthorize 拦截（真防线），绕过前端直接调接口只会拿 1005。
 */
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { logout as logoutApi } from '@/api/auth'
import { useUserStore } from '@/stores/user'
import { filterMenus } from '@/utils/menu'

const route = useRoute()
const router = useRouter()
const store = useUserStore()

/** 当前用户可见菜单（过滤逻辑在纯函数层，可被 Vitest 直接断言） */
const visibleMenus = computed(() => filterMenus(store.perms))

/** el-menu 高亮：用路由 path 当 index（router 模式下 el-menu 原生支持） */
const activePath = computed(() => route.path)

/** 登出：先调服务端拉黑 jti，无论成败都清本地态（本地态不依赖服务端可达） */
async function handleLogout() {
  try {
    await ElMessageBox.confirm('退出后当前令牌立即失效，确认退出？', '登出', {
      confirmButtonText: '退出', cancelButtonText: '取消', type: 'warning'
    })
  } catch {
    return // 用户取消（ElMessageBox 以 reject 表达取消，这是 Element Plus 约定）
  }
  try {
    await logoutApi()
  } catch {
    /* 服务端可能已判定令牌失效（401 被拦截器 toast）：本地照样清 */
  }
  store.logout()
  ElMessage.success('已退出登录')
  router.push('/login')
}
</script>

<template>
  <el-container class="admin-layout">
    <el-aside width="200px">
      <div class="brand"><router-link to="/">个人博客 · 后台</router-link></div>
      <el-menu :default-active="activePath" router>
        <el-menu-item v-for="m in visibleMenus" :key="m.key" :index="`/admin/${m.path}`.replace(/\/$/, '')">
          {{ m.label }}
        </el-menu-item>
      </el-menu>
    </el-aside>
    <el-container>
      <el-header class="admin-header">
        <span>{{ store.user?.nickname || store.user?.username || '未登录' }}</span>
        <el-button size="small" @click="handleLogout">退出登录</el-button>
      </el-header>
      <el-main>
        <router-view />
      </el-main>
    </el-container>
  </el-container>
</template>

<style scoped>
.admin-layout {
  height: 100vh;
}
.el-aside {
  border-right: 1px solid var(--el-border-color-light);
}
.brand {
  padding: 16px;
  font-weight: 600;
}
.admin-header {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 12px;
  border-bottom: 1px solid var(--el-border-color-light);
}
</style>
