<script setup>
/**
 * 404 页（M10）—— 通配路由兜底：手输错误地址、删文后的旧链接、链接腐烂，
 * 全部落到这里而不是白屏（浏览器白屏 + 无任何出口是"未打通"最直观的症状）。
 * 独立于 SiteLayout（/​:pathMatch 是顶层路由）：不引布局组件是为了让错误页
 * 零依赖——它必须连"数据加载失败"这种最脏环境都能渲染。
 */
import { useRouter } from 'vue-router'
const router = useRouter()
</script>

<template>
  <div class="nf">
    <h1>404</h1>
    <p>你访问的页面不存在，或它曾经存在、现在躺在回收站里。</p>
    <div class="acts">
      <el-button type="primary" @click="router.push('/')">回首页</el-button>
      <el-button @click="router.back()">返回上一页</el-button>
    </div>
  </div>
</template>

<style scoped>
.nf { max-width: 520px; margin: 12vh auto; text-align: center; padding: 0 16px; }
.nf h1 { font-size: 72px; margin: 0; color: #409eff; }
.nf p { color: #909399; margin: 10px 0 24px; }
.acts { display: flex; gap: 12px; justify-content: center; }
</style>
