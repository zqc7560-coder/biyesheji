/**
 * 站点门面参数共享加载器（M10）。
 *
 * 背景：M8 时 HomeView 自己拉 GET /api/system/config；M10 加全站顶栏（SiteLayout）
 * 后出现第二个消费者——两处各拉一次，轻则重复请求，重则两处回退文案漂移
 * （一个显"个人博客系统"一个显空）。收敛为模块级单例 Promise：
 * ① 全应用生命周期最多打一次公开参数接口（该数据变更频率≈每周一次，
 *    会话内缓存完全成立，无需失效机制）；
 * ② 失败缓存 null——调用方一律回退默认名，永不重试风暴（失败即降级定案）。
 */
import { ref } from 'vue'
import { fetchPublicConfig } from '@/api/system'

/** 响应式门面快照：{name, logo, icp}——加载完成前是默认值（首屏永不空白） */
export const siteInfo = ref({ name: '个人博客系统', logo: '', icp: '' })

let inflight = null

/**
 * 注入站点参数（组件 setup 里调一次即可；返回共享响应式 ref 本体——
 * 组件里 `site.name` 经模板自动解包，异步加载完成后视图自然刷新。
 * 纯数据映射规则抽成 mapPublicConfig 导出给 Vitest（vue 组件不可测环境的补偿）。
 */
export function injectSite() {
  if (!inflight) {
    inflight = fetchPublicConfig()
      .then((c) => { siteInfo.value = mapPublicConfig(c); return siteInfo.value })
      .catch(() => { inflight = null })   // 清 inflight：允许下一次挂载重试一次（登录页→首页的冷重启路径）
  }
  return siteInfo
}

/** 公开参数 → 门面映射：非对象/缺键全部走默认（后端白名单键名是 site_name/site_logo/site_icp） */
export function mapPublicConfig(c) {
  const o = c && typeof c === 'object' ? c : {}
  return {
    name: String(o.site_name || '').trim() || '个人博客系统',
    logo: String(o.site_logo || ''),
    icp: String(o.site_icp || '')
  }
}
