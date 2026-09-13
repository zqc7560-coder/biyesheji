<script setup>
/**
 * 用户中心总览（M3）：原 M2 占位页升级为入口卡片导航 + 当前身份展示。
 * 资料编辑/改密/头像的 M2 接口能力保留，其表单页归 M10 全站页面打通时统一铺开。
 */
import { useRouter } from 'vue-router'
import { useUserStore } from '@/stores/user'
const router = useRouter()
const store = useUserStore()
</script>

<template>
  <div>
    <h2>你好，{{ store.user?.nickname }}</h2>
    <p class="tip">{{ store.user?.bio || '（还没有个人简介，资料设置在 M10 铺开）' }}</p>
    <div class="cards">
      <el-card shadow="hover" class="entry" @click="router.push('/user/articles')">
        <h3>📚 我的文章</h3><p>草稿箱与已发布管理</p>
      </el-card>
      <!-- M5：我的评论入口（UC-U-12；待审/被屏蔽状态在页内有标签可见） -->
      <el-card shadow="hover" class="entry" @click="router.push('/user/comments')">
        <h3>💬 我的评论</h3><p>发言记录与审核状态</p>
      </el-card>
      <el-card shadow="hover" class="entry" @click="router.push('/user/articles/new')">
        <h3>✍ 写文章</h3><p>Markdown 编辑器（实时预览 / 自动草稿兜底）</p>
      </el-card>
      <!-- M10：账号设置入口（UC-U-02 资料/头像 + UC-U-03 改密，M2 接口能力的表单页） -->
      <el-card shadow="hover" class="entry" @click="router.push('/user/settings')">
        <h3>⚙ 账号设置</h3><p>资料 / 头像 / 修改密码</p>
      </el-card>
      <el-card shadow="hover" class="entry" @click="router.push('/')">
        <h3>🏠 回到站点</h3><p>公开列表与搜索</p>
      </el-card>
    </div>
  </div>
</template>

<style scoped>
.tip { color: #909399; font-size: 13px; }
.cards { display: flex; gap: 16px; flex-wrap: wrap; margin-top: 16px; }
.entry { width: 240px; cursor: pointer; }
.entry h3 { margin: 0 0 6px; }
.entry p { margin: 0; color: #909399; font-size: 13px; }
</style>
