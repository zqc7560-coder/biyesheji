<script setup>
/**
 * 账号设置页（M10）—— UC-U-02 资料/头像 + UC-U-03 改密 的表单页落地。
 * 台账 M3 备注「资料设置 M10 统一铺开」在此闭环：M2 只有接口能力没有页面。
 *
 * 三段式一屏（资料 / 头像 / 改密）而不拆三个路由：都是"账号"这一个心智对象，
 * 拆路由只是把一次页面跳转的税加给找设置入口的用户。
 *
 * 改密的收尾流程是本页最讲究的一处：后端成功后旧令牌已被拉黑（全端下线），
 * 此刻若还停留在页面里，下一次任何请求都会 1001 → 拦截器踢去登录页并 toast
 * "登录已失效"——用户莫名其妙。正确姿势：拿到成功响应立即主动 store.logout()
 * + 跳 /login?redirect=/user/settings（重登后回到本页，把"改完密接着弄资料"
 * 的动线接上）。message 用后端文案「密码已更新，请重新登录」。
 */
import { ref, reactive, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { fetchProfile, updateProfile, changePassword, uploadAvatar } from '@/api/user'
import { validateProfile, validatePasswordChange, avatarPickHint } from '@/utils/profile'
import { precheckImage } from '@/utils/fileUpload'
import { useUserStore } from '@/stores/user'

const router = useRouter()
const store = useUserStore()

const profile = reactive({ nickname: '', email: '', bio: '' })
const profileErrs = ref({})
const savingProfile = ref(false)

const pwd = reactive({ oldPassword: '', newPassword: '', confirmPassword: '' })
const pwdErrs = ref({})
const savingPwd = ref(false)

const avatarUploading = ref(false)

onMounted(async () => {
  try {
    const p = await fetchProfile()   // 缺省=自己（含 email，本人场景后端不抹 PII）
    Object.assign(profile, { nickname: p.nickname || '', email: p.email || '', bio: p.bio || '' })
  } catch { /* 拦截器已 toast；表单留空但不炸页 */ }
})

/** 资料保存：客户端校验对齐后端 DTO 规则（utils/profile.js，已 Vitest），过闸才发请求 */
async function saveProfile() {
  profileErrs.value = validateProfile(profile)
  if (Object.keys(profileErrs.value).length) return
  savingProfile.value = true
  try {
    const fresh = await updateProfile({ nickname: profile.nickname.trim(), email: profile.email.trim(), bio: profile.bio })
    // 同步刷新 store 快照（顶栏昵称/头像立刻见新值；不改 localStorage 缓存结构——
    // applyLogin 语义就是"整包用户快照落盘"，复用它而不是手写 user 字段）
    store.applyLogin(store.token, fresh)
    ElMessage.success('资料已更新')
  } finally {
    savingProfile.value = false
  }
}

/** 头像上传：预检（扩展名+5MB）→ POST /api/users/avatar → 回写快照 */
async function pickAvatar(ev) {
  const file = ev.target.files?.[0]
  ev.target.value = ''   // 允许连续选同一文件再次触发 change
  if (!file) return
  const check = precheckImage(file)
  if (!check.ok) { ElMessage.warning(check.message); return }
  avatarUploading.value = true
  try {
    const fresh = await uploadAvatar(file)
    store.applyLogin(store.token, fresh)
    ElMessage.success('头像已更新')
  } catch (e) {
    ElMessage.error(e.message || '头像上传失败')
  } finally {
    avatarUploading.value = false
  }
}

/** 改密：成功后立即本地登出+跳登录（见文件头"收尾流程"注释） */
async function submitPassword() {
  pwdErrs.value = validatePasswordChange(pwd)
  if (Object.keys(pwdErrs.value).length) return
  savingPwd.value = true
  try {
    await changePassword({ oldPassword: pwd.oldPassword, newPassword: pwd.newPassword })
    ElMessage.success('密码已更新，请重新登录')
    store.logout()
    router.push({ path: '/login', query: { redirect: '/user/settings' } })
  } finally {
    savingPwd.value = false
  }
}
</script>

<template>
  <div class="settings">
    <h2>账号设置</h2>

    <el-card class="sec">
      <template #header>基本资料</template>
      <el-form label-width="70px" style="max-width: 460px">
        <el-form-item label="昵称" :error="profileErrs.nickname">
          <el-input v-model="profile.nickname" maxlength="50" show-word-limit />
        </el-form-item>
        <el-form-item label="邮箱" :error="profileErrs.email">
          <el-input v-model="profile.email" maxlength="100" />
        </el-form-item>
        <el-form-item label="简介" :error="profileErrs.bio">
          <el-input v-model="profile.bio" type="textarea" :rows="3" maxlength="500" show-word-limit
                    placeholder="一句话介绍自己（公开展示在作者主页）" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="savingProfile" @click="saveProfile">保存资料</el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <el-card class="sec">
      <template #header>头像</template>
      <div class="avatar-row">
        <img v-if="store.user?.avatar" :src="store.user.avatar" class="pic" alt="当前头像" />
        <el-avatar v-else :size="64">{{ (store.user?.nickname || 'U').slice(0, 1) }}</el-avatar>
        <div>
          <label class="pick">
            <input type="file" accept=".png,.jpg,.jpeg,.webp,.gif" hidden @change="pickAvatar" />
            <el-button :loading="avatarUploading">选择图片上传</el-button>
          </label>
          <p class="tip">{{ avatarPickHint() }}（服务端还做 MIME 与魔数双重校验）</p>
        </div>
      </div>
    </el-card>

    <el-card class="sec">
      <template #header>修改密码</template>
      <el-form label-width="70px" style="max-width: 460px">
        <el-form-item label="旧密码" :error="pwdErrs.oldPassword">
          <el-input v-model="pwd.oldPassword" type="password" show-password autocomplete="current-password" />
        </el-form-item>
        <el-form-item label="新密码" :error="pwdErrs.newPassword">
          <el-input v-model="pwd.newPassword" type="password" show-password autocomplete="new-password"
                    placeholder="≥8 位且包含字母和数字" />
        </el-form-item>
        <el-form-item label="确认" :error="pwdErrs.confirmPassword">
          <el-input v-model="pwd.confirmPassword" type="password" show-password autocomplete="new-password" />
        </el-form-item>
        <el-form-item>
          <el-button type="danger" :loading="savingPwd" @click="submitPassword">修改密码</el-button>
          <span class="tip" style="margin-left:10px">改密后所有登录端立即下线，需重新登录</span>
        </el-form-item>
      </el-form>
    </el-card>
  </div>
</template>

<style scoped>
.settings { max-width: 720px; }
.settings h2 { margin: 0 0 14px; }
.sec { margin-bottom: 16px; }
.avatar-row { display: flex; gap: 16px; align-items: center; }
.pic { width: 64px; height: 64px; border-radius: 50%; object-fit: cover; }
.pick { cursor: pointer; }
.tip { color: #909399; font-size: 12px; margin: 6px 0 0; }
</style>
