<script setup>
/** 登录页占位（M0）：表单框架 + 路由 redirect 回跳逻辑；真实调 /api/auth/login 在 M2 接入 */
import { ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'

const route = useRoute()
const router = useRouter()
const form = ref({ username: '', password: '' })

function submit() {
  // M2 前未接后端：仅演示守卫回跳参数传递正确
  router.push(route.query.redirect || '/')
}
</script>

<template>
  <main style="max-width: 360px; margin: 100px auto">
    <h2>登录</h2>
    <el-form @submit.prevent="submit">
      <el-form-item><el-input v-model="form.username" placeholder="用户名" /></el-form-item>
      <el-form-item><el-input v-model="form.password" type="password" placeholder="密码" /></el-form-item>
      <el-button type="primary" native-type="submit" style="width:100%">登 录</el-button>
    </el-form>
  </main>
</template>
