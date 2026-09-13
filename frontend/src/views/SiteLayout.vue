<script setup>
/**
 * 全站公开顶栏（M10）——「前端全部页面打通」的骨架件。
 *
 * 为什么需要一个共享 Header 而不是各页自写导航（M0~M9 期间 HomeView 里塞了
 * 站点名，详情页连返回按钮都没有）：归档页、作者主页、账号设置页、404 页都是
 * 新增的公开路由，没有统一出口用户就"进得去出不来"。路由树里所有公开页
 * （/、/articles/:id、/archive、/u/:id、/user/**、/login）套同一层 SiteLayout，
 * 后台 AdminLayout 刻意<b>不</b>套——两套布局分属不同心智空间（M0 根组件注释
 * 里"布局不属于根组件"的约定继续成立）。
 *
 * 登录态渲染：只读 userStore（Pinia 单一事实源），不自己碰 localStorage，
 * 避免"守卫查存储、组件查 store"两套状态漂移（stores/user.js 头注释同一条坑）。
 */
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { logout as logoutApi } from '@/api/auth'
import { useUserStore } from '@/stores/user'
import { injectSite } from '@/utils/site'

const route = useRoute()
const router = useRouter()
const store = useUserStore()

/**
 * 站点门面参数（M8 已有公开口）：顶栏标题优先用 site_name。
 * injectSite 是纯函数（utils/site.js）：失败/未加载时回退默认名，首屏永不空白。
 */
const site = injectSite()

const NAVS = [
  { path: '/', label: '首页' },
  { path: '/archive', label: '归档' }
]

/** 高亮判定：首页精确匹配，其余按前缀（/articles/12 不点亮"首页"但点亮"文章"语境） */
function isActive(path) {
  return path === '/' ? route.path === '/' : route.path.startsWith(path)
}

async function logout() {
  try { await logoutApi() } catch { /* 令牌可能已失效，本地照清 */ }
  store.logout()
  ElMessage.success('已退出登录')
  router.push('/login')
}
</script>

<template>
  <div class="site-shell">
    <header class="site-head">
      <div class="head-inner">
        <router-link to="/" class="brand">
          <img v-if="site.logo" :src="site.logo" class="brand-logo" alt="站点Logo" />
          <span>{{ site.name }}</span>
        </router-link>
        <nav class="navs">
          <router-link v-for="n in NAVS" :key="n.path" :to="n.path"
                       :class="{ on: isActive(n.path) }">{{ n.label }}</router-link>
        </nav>
        <div class="who">
          <template v-if="store.isLoggedIn">
            <router-link to="/user" class="me">
              <img v-if="store.user?.avatar" :src="store.user.avatar" class="avatar" alt="" />
              <span>{{ store.user?.nickname || store.user?.username }}</span>
            </router-link>
            <el-button size="small" @click="router.push('/user/articles/new')">✍<span class="write-label"> 写文章</span></el-button>
            <el-button v-if="store.isAdmin" size="small" type="primary" plain
                       @click="router.push('/admin')">后台</el-button>
            <el-button size="small" link @click="logout">退出</el-button>
          </template>
          <template v-else>
            <el-button size="small" @click="router.push('/login')">登录 / 注册</el-button>
          </template>
        </div>
      </div>
    </header>
    <main class="site-body"><router-view /></main>
    <footer v-if="site.icp" class="site-foot">{{ site.icp }}</footer>
  </div>
</template>

<style scoped>
.site-shell { min-height: 100vh; display: flex; flex-direction: column; background: #f7f8fa; }
.site-head { position: sticky; top: 0; z-index: 20; background: #fff; border-bottom: 1px solid #ebeef5; }
.head-inner { max-width: 1080px; margin: 0 auto; padding: 0 16px; height: 52px; display: flex; align-items: center; gap: 20px; }
.brand { display: flex; align-items: center; gap: 8px; font-weight: 700; font-size: 16px; color: #303133; text-decoration: none; }
.brand-logo { height: 24px; max-width: 80px; object-fit: contain; }
.navs { display: flex; gap: 16px; }
.navs a { text-decoration: none; color: #606266; font-size: 14px; padding: 4px 2px; border-bottom: 2px solid transparent; }
.navs a.on { color: #409eff; border-bottom-color: #409eff; }
.who { margin-left: auto; display: flex; align-items: center; gap: 10px; }
.me { display: flex; align-items: center; gap: 6px; text-decoration: none; color: #606266; font-size: 14px; }
.avatar { width: 24px; height: 24px; border-radius: 50%; object-fit: cover; }
.site-body { flex: 1; }
.site-foot { text-align: center; color: #909399; font-size: 12px; padding: 16px; }
/* M15 移动端断点系统回归：顶栏是全站最高频元素，窄屏三件事——
   ① brand/nav/who 允许换行成两行（52px 固定高改 min-height，防挤压裁字）；
   ② "写文章"按钮只留 ✍ 图标文案（动线保留、宽度砍掉，全站唯一入口不能藏进抽屉）；
   ③ 元素间距收紧。后台 AdminLayout 面向桌面场景（部署与测试 W13 手工项口径），
   刻意不做移动折叠——不是遗漏，是范围决策。 */
@media (max-width: 768px) {
  .head-inner { height: auto; min-height: 52px; flex-wrap: wrap; padding: 8px 12px; gap: 8px 12px; }
  .navs { gap: 10px; }
  .who { gap: 6px; }
  .write-label { display: none; }
}
</style>
