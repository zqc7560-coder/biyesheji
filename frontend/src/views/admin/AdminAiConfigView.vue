<script setup>
/**
 * 后台-AI 模型配置页（M6，课题重点）—— UC-A-06：模型配置 CRUD/设默认/连通测试。
 * 对接《API接口设计.md》§7 /api/admin/ai/** 全家；权限点 ai:config。
 *
 * 安全交互约定（《AI模块设计.md》§2.2）：
 * - Key 输入框"写后不可见"：列表只有掩码（sk-****abcd），编辑时留空=不变更，
 *   永远不回填明文（后端结构上也不回传，见 AiConfigVo 无 Key 字段）；
 * - 内置行（is_builtin=1）：删除按钮隐藏、名称/端点/模型三输入框禁用——
 *   前端只是体验层，后端 4006 拒绝才是防线；
 * - "连接测试"是唯一真花钱的按钮：按下即禁用防连点，结果（ok/4101/4102）
 *   以行内消息呈现，测试记录同步进调用日志（可审计"谁在试 Key"）。
 */
import { ref, reactive, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  fetchAiConfigs, createAiConfig, updateAiConfig, deleteAiConfig,
  setAiDefault, testAiConfig
} from '@/api/ai'
import { keyStatusTag, isBuiltinConfig } from '@/utils/ai'

const rows = ref([])
const total = ref(0)
const query = reactive({ pageNum: 1, pageSize: 10 })
const loading = ref(false)
/** 正在做连通测试的行 id（Set 语义用单值即可：一次只测一行） */
const testingId = ref(null)

const dialog = reactive({
  visible: false, mode: 'create', id: null, builtin: false,
  form: {}
})

async function load() {
  loading.value = true
  try {
    const page = await fetchAiConfigs(query.pageNum, query.pageSize)
    rows.value = page.list
    total.value = page.total
  } finally {
    loading.value = false
  }
}
onMounted(load)

function openCreate() {
  Object.assign(dialog, {
    visible: true, mode: 'create', id: null, builtin: false,
    form: {
      name: '', provider: 'openai_compatible', baseUrl: '', apiKey: '', model: '',
      temperature: 0.3, maxTokens: 512, timeoutMs: 30000,
      summaryPrompt: '', tagPrompt: '', isDefault: false
    }
  })
}

function openEdit(row) {
  Object.assign(dialog, {
    visible: true, mode: 'edit', id: row.id, builtin: isBuiltinConfig(row),
    form: {
      // apiKey 刻意不回填：留空=保持原 Key（§7 PUT 语义）
      name: row.name, provider: row.provider, baseUrl: row.baseUrl, apiKey: '',
      model: row.model, temperature: Number(row.temperature), maxTokens: row.maxTokens,
      timeoutMs: row.timeoutMs, summaryPrompt: row.summaryPrompt || '',
      tagPrompt: row.tagPrompt || '', isDefault: row.isDefault === 1
    }
  })
}

async function submit() {
  const f = dialog.form
  if (!f.name?.trim() || !f.baseUrl?.trim() || !f.model?.trim()) {
    ElMessage.warning('名称、Base URL、模型名为必填')
    return
  }
  const payload = { ...f, summaryPrompt: f.summaryPrompt || null, tagPrompt: f.tagPrompt || null }
  try {
    if (dialog.mode === 'create') {
      await createAiConfig(payload)
      ElMessage.success('配置已创建')
    } else {
      await updateAiConfig(dialog.id, payload)
      ElMessage.success('配置已更新')
    }
    dialog.visible = false
    await load()
  } catch { /* 拦截器已 toast（4006/4007/400 等均含后端中文文案） */ }
}

async function remove(row) {
  await ElMessageBox.confirm(
    `删除「${row.name}」？删除为停用保留行（审计链不断），默认配置被删后用户端将提示未配置。`,
    '确认删除', { type: 'warning' })
  await deleteAiConfig(row.id)
  ElMessage.success('已删除')
  load()
}

async function makeDefault(row) {
  await setAiDefault(row.id)
  ElMessage.success(`「${row.name}」已是全站默认模型`)
  load()
}

/** 连通测试：真调上游（几秒），行级 loading；错误码 4101/4102 由拦截器 toast 精确文案 */
async function test(row) {
  testingId.value = row.id
  try {
    const r = await testAiConfig(row.id)
    ElMessage.success(`连接正常（${r.latencyMs}ms，回复「${r.reply}」）`)
  } catch { /* 4101/4102 toast 在拦截器 */ } finally {
    testingId.value = null
  }
}
</script>

<template>
  <div class="ai-config-page">
    <div class="toolbar">
      <h3>AI 模型配置</h3>
      <el-button type="primary" @click="openCreate">新增配置</el-button>
    </div>
    <el-table :data="rows" v-loading="loading" size="small">
      <el-table-column prop="name" label="名称" min-width="140">
        <template #default="{ row }">
          {{ row.name }}
          <el-tag v-if="isBuiltinConfig(row)" size="small" effect="plain">内置</el-tag>
          <el-tag v-if="row.isDefault === 1" size="small" type="success">默认</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="model" label="模型 ID" min-width="130" />
      <el-table-column prop="baseUrl" label="Base URL" min-width="200" show-overflow-tooltip />
      <el-table-column label="Key 状态" width="150">
        <template #default="{ row }">
          <el-tag :type="keyStatusTag(row).type" size="small">{{ keyStatusTag(row).label }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="启停" width="70">
        <template #default="{ row }">
          <el-tag :type="row.enabled === 1 ? 'success' : 'info'" size="small" effect="plain">
            {{ row.enabled === 1 ? '启用' : '停用' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="230" fixed="right">
        <template #default="{ row }">
          <el-button size="small" :loading="testingId === row.id" @click="test(row)">连接测试</el-button>
          <el-button size="small" @click="openEdit(row)">编辑</el-button>
          <el-button v-if="row.isDefault !== 1" size="small" @click="makeDefault(row)">设默认</el-button>
          <el-button v-if="!isBuiltinConfig(row)" size="small" type="danger" @click="remove(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>
    <el-pagination class="pager" layout="total, prev, pager, next" :total="total"
                   v-model:current-page="query.pageNum" :page-size="query.pageSize" @current-change="load" />

    <!-- 编辑弹窗：Key 输入框写后不可见（编辑态 placeholder 明示"留空不变更"） -->
    <el-dialog v-model="dialog.visible" :title="dialog.mode === 'create' ? '新增 AI 配置' : '编辑 AI 配置'"
               width="560px">
      <el-form label-width="110px">
        <el-form-item label="名称" required>
          <el-input v-model="dialog.form.name" :disabled="dialog.builtin" maxlength="50" />
        </el-form-item>
        <el-form-item label="厂商标识">
          <el-input v-model="dialog.form.provider" :disabled="dialog.builtin" placeholder="deepseek / dashscope / …" />
        </el-form-item>
        <el-form-item label="Base URL" required>
          <el-input v-model="dialog.form.baseUrl" :disabled="dialog.builtin"
                    placeholder="https://api.deepseek.com" maxlength="200" />
        </el-form-item>
        <el-form-item label="API Key">
          <el-input v-model="dialog.form.apiKey" type="password" show-password
                    :placeholder="dialog.mode === 'edit' ? '留空 = 保持原 Key 不变' : '仅写入不回显，落库前 AES-GCM 加密'"
                    maxlength="256" />
        </el-form-item>
        <el-form-item label="模型 ID" required>
          <el-input v-model="dialog.form.model" :disabled="dialog.builtin" maxlength="80" />
        </el-form-item>
        <el-form-item label="温度">
          <el-input-number v-model="dialog.form.temperature" :min="0" :max="2" :step="0.1" :precision="2" />
        </el-form-item>
        <el-form-item label="max_tokens">
          <el-input-number v-model="dialog.form.maxTokens" :min="1" :max="4096" :step="64" />
        </el-form-item>
        <el-form-item label="超时(ms)">
          <el-input-number v-model="dialog.form.timeoutMs" :min="1000" :max="60000" :step="1000" />
        </el-form-item>
        <el-form-item label="摘要模板">
          <el-input v-model="dialog.form.summaryPrompt" type="textarea" :rows="2"
                    placeholder="留空 = 使用内置 ai_summary_v1（自定义即参与缓存键版本）" maxlength="2000" />
        </el-form-item>
        <el-form-item label="设为默认">
          <el-switch v-model="dialog.form.isDefault" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialog.visible = false">取消</el-button>
        <el-button type="primary" @click="submit">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.ai-config-page { background: #fff; padding: 16px; border-radius: 6px; }
.toolbar { display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px; }
.pager { margin-top: 12px; justify-content: flex-end; }
</style>
