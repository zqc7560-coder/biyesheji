<script setup>
/**
 * 后台-系统设置页（M8，UC-A-09）：GET /api/admin/system/config 动态表单
 * + PUT 批量修改（menu.js 里声明的 settings 入口在此落地，权限点 config:manage）。
 *
 * 表单渲染按后端 config_type 决定控件（boolean→开关 / number→数字框 / string→文本框）：
 * 类型事实源在服务端 sys_config 列，前端不自造字段清单——将来管理员在库里加键、
 * 只要落进可编辑白名单，这里零改动自动出现（数据驱动的后台表单都该这么做）。
 *
 * 提交策略：只把"值发生过变化的键"发给后端（dirty diff）。整表提交的并发语义是
 * "后保存者全量覆盖"，diff 提交把竞态窗口缩到用户真正改过的键上。
 */
import { ref, reactive, computed, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { fetchAdminConfigList, updateSystemConfig } from '@/api/system'
import { uploadImage } from '@/api/file'

const loading = ref(false)
const saving = ref(false)
/** 服务端原值快照（diff 基准）与当前表单值 */
const original = ref({})
const form = reactive({})
/** 元数据（key → {type, description}），控件类型与 tooltip 来源 */
const meta = ref({})
/** site_logo 上传中态（文件类字段的即时反馈，与 AI 按钮 loading 同族处理） */
const logoUploading = ref(false)

async function load() {
  loading.value = true
  try {
    const items = await fetchAdminConfigList()
    const orig = {}
    const m = {}
    for (const it of items) {
      orig[it.key] = it.value
      m[it.key] = { type: it.type, description: it.description }
      form[it.key] = it.value
    }
    original.value = orig
    meta.value = m
  } finally {
    loading.value = false
  }
}

/** 键的展示顺序沿用后端返回序（= sys_config 主键序 = 种子语义分组） */
const keys = computed(() => Object.keys(form))

const dirtyCount = computed(() =>
  keys.value.filter((k) => String(form[k]) !== String(original.value[k])).length)

async function save() {
  const items = keys.value
    .filter((k) => String(form[k]) !== String(original.value[k]))
    .map((k) => ({ key: k, value: String(form[k]).trim() }))
  if (!items.length) {
    ElMessage.info('没有需要保存的修改')
    return
  }
  saving.value = true
  try {
    const fresh = await updateSystemConfig(items)
    ElMessage.success(`已保存 ${items.length} 项`)
    // 用响应回传的全量清单重建表单（服务端是事实源，本地值只是草稿）
    original.value = {}
    for (const it of fresh) {
      original.value[it.key] = it.value
      form[it.key] = it.value
    }
  } finally {
    saving.value = false
  }
}

/** Logo 行内上传：走 M8 通用通道（type=article 复用图片规则），成功即回填 URL */
async function pickLogo(ev) {
  const file = ev.target.files?.[0]
  ev.target.value = ''          // 立即重置：同名文件再选也能触发 change
  if (!file) return
  logoUploading.value = true
  try {
    const r = await uploadImage(file, 'cover')
    form.site_logo = r.url
    ElMessage.success('Logo 已上传，点击"保存修改"生效')
  } catch (e) {
    ElMessage.error(e.message || 'Logo 上传失败')
  } finally {
    logoUploading.value = false
  }
}

onMounted(load)
</script>

<template>
  <div class="cfg-page" v-loading="loading">
    <div class="head">
      <h2>系统设置</h2>
      <div class="head-actions">
        <el-button type="primary" :loading="saving" :disabled="!dirtyCount" @click="save">
          保存修改<template v-if="dirtyCount">（{{ dirtyCount }}）</template>
        </el-button>
        <el-button @click="load">放弃修改并重载</el-button>
      </div>
    </div>
    <el-alert type="info" :closable="false" style="margin-bottom:14px"
              title="参数即时生效：注册开关/评论审核影响全站写入闸门，AI 总开关是成本熔断位；未在清单中的键属于其它模块专职配置页。" />
    <el-form label-width="180px" label-position="left">
      <el-form-item v-for="k in keys" :key="k">
        <template #label>
          <el-tooltip :content="meta[k]?.description || ''" placement="top">
            <span>{{ k }}</span>
          </el-tooltip>
        </template>
        <!-- 控件按后端 config_type 分发：类型系统是"库里的数据"而不是"前端的常量" -->
        <el-switch v-if="meta[k]?.type === 'boolean'"
                   :model-value="form[k] === 'true'"
                   @update:model-value="(v) => (form[k] = String(v))" />
        <el-input-number v-else-if="meta[k]?.type === 'number'"
                         :min="0" :model-value="Number(form[k])"
                         @update:model-value="(v) => (form[k] = String(v ?? 0))" />
        <div v-else-if="k === 'site_logo'" class="logo-row">
          <el-input v-model="form[k]" placeholder="留空则顶栏显示站点名文字" style="max-width:420px" />
          <label class="upload-btn">
            <input type="file" accept=".png,.jpg,.jpeg,.webp,.gif" hidden @change="pickLogo" />
            <el-button :loading="logoUploading" size="small">上传 Logo</el-button>
          </label>
          <img v-if="form[k]" :src="form[k]" class="logo-prev" alt="logo预览" />
        </div>
        <el-input v-else v-model="form[k]" style="max-width:420px" />
      </el-form-item>
    </el-form>
  </div>
</template>

<style scoped>
.cfg-page { max-width: 860px; }
.head { display: flex; justify-content: space-between; align-items: center; }
.head-actions { display: flex; gap: 8px; }
.logo-row { display: flex; align-items: center; gap: 10px; }
.upload-btn { cursor: pointer; }
.logo-prev { height: 32px; max-width: 96px; object-fit: contain; border: 1px solid #ebeef5; border-radius: 4px; padding: 2px; background: #fff; }
</style>
