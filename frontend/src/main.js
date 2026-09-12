/**
 * 应用入口：装配 Vue → Pinia → Router → Element Plus。
 *
 * 顺序说明：Pinia 必须先于 Router mount 前注册——路由守卫里会读
 * useUserStore() 判断登录态，若 store 未装好会拿到未激活实例报错。
 * Element Plus 整包引入是骨架期最简方案；按需引入（unplugin-vue-components）
 * 属于构建优化，放 M9/M12 性能项一起做，避免骨架期引入编译插件增加心智负担。
 */
import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import App from './App.vue'
import router from './router'

const app = createApp(App)

app.use(createPinia())
app.use(router)
app.use(ElementPlus)

app.mount('#app')
