<script setup>
/**
 * 作者主页（M10）—— 详情页署名区点进去的公开页（/u/:id）。
 * 数据口：GET /api/users/{id}/articles（公开，仅已发布）。
 *
 * 为什么匿名不调 GET /api/users/profile?userId=：该接口要登录（§3"登录"级），
 * 而 request 拦截器对 401 的动作是"清令牌跳登录"——游客进作者主页会被弹去登录页，
 * 属于用错接口把公开页做成了会员墙。降级方案：昵称/头像从文章列表 VO 的
 * author 投影里取（后端 toBriefVOList 恒带 author 摘要，零额外请求）。
 * 登录用户额外拉 profile 补 bio 简介（拿失败静默——bio 是锦上添花字段）。
 */
import { ref, computed, onMounted, watch } from 'vue'
import { useRoute } from 'vue-router'
import { fetchUserArticles } from '@/api/article'
import { fetchProfile } from '@/api/user'
import { useUserStore } from '@/stores/user'

const route = useRoute()
const store = useUserStore()
const id = computed(() => Number(route.params.id))

const data = ref({ list: [], total: 0 })
const profile = ref(null)

async function load() {
  data.value = { list: [], total: 0 }
  profile.value = null
  const res = await fetchUserArticles(id.value, { pageNum: 1, pageSize: 50 })
  data.value = res
  // 仅登录用户才探测资料接口（匿名 401 会被拦截器弹去登录——见文件头注释）
  if (store.isLoggedIn) {
    try { profile.value = await fetchProfile(id.value) } catch { /* 他人 bio 拉不到不阻塞文章列表 */ }
  }
}

/** 展示身份：profile 优先（更全），回退文章投影里的 author（游客路径恒用它） */
const shown = computed(() => {
  const a = data.value.list[0]?.author
  return {
    nickname: profile.value?.nickname || a?.nickname || `用户 ${id.value}`,
    avatar: profile.value?.avatar || a?.avatar || '',
    bio: profile.value?.bio || ''
  }
})

onMounted(load)
watch(id, load)
</script>

<template>
  <div class="author">
    <header class="hero">
      <img v-if="shown.avatar" :src="shown.avatar" class="pic" alt="" />
      <el-avatar v-else :size="56">{{ shown.nickname.slice(0, 1) }}</el-avatar>
      <div>
        <h2>{{ shown.nickname }}</h2>
        <p class="bio">{{ shown.bio || '这位作者还没有简介' }}</p>
        <p class="stat">已发布 {{ data.total }} 篇文章</p>
      </div>
    </header>
    <el-empty v-if="!data.list.length" description="TA 还没有已发布的文章" />
    <ul class="list">
      <li v-for="a in data.list" :key="a.id">
        <router-link :to="`/articles/${a.id}`">{{ a.title }}</router-link>
        <span class="meta">{{ String(a.publishedAt || '').slice(0, 10) }} · {{ a.viewCount ?? 0 }} 浏览</span>
      </li>
    </ul>
  </div>
</template>

<style scoped>
.author { max-width: 760px; margin: 24px auto; padding: 0 16px; }
.hero { display: flex; gap: 14px; align-items: center; margin-bottom: 18px; }
.hero h2 { margin: 0 0 4px; }
.pic { width: 56px; height: 56px; border-radius: 50%; object-fit: cover; }
.bio { margin: 0; color: #606266; font-size: 13px; }
.stat { margin: 2px 0 0; color: #909399; font-size: 12px; }
.list { list-style: none; margin: 0; padding: 0; }
.list li { padding: 8px 0; border-bottom: 1px dashed #ebeef5; display: flex; justify-content: space-between; gap: 10px; }
.list a { color: #303133; text-decoration: none; }
.list a:hover { color: #409eff; }
.meta { color: #909399; font-size: 12px; flex-shrink: 0; }
</style>
