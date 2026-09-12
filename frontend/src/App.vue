<script setup>
/**
 * 根组件：只放 router-view 骨架。
 * 布局（前台导航栏 / 后台侧边菜单）不写在这里——两套布局分属不同路由树
 * （/ 前台、/admin 后台），由各自 layout 组件承担，根组件保持纯净。
 */
</script>

<template>
  <router-view />
</template>

<style>
html, body, #app {
  height: 100%;
  margin: 0;
  font-family: 'Helvetica Neue', Helvetica, 'PingFang SC', 'Microsoft YaHei', Arial, sans-serif;
}
</style>
