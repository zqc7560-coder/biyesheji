<script setup>
/**
 * 后台-标签管理页（M4）—— UC-A-04：标签增删改查、合并。
 * 对接《API接口设计.md》§5 标签五接口；权限点 tag:manage（ADMIN）。
 *
 * 页面结构：平铺表格（标签没有层级，比分类页简单一截）+ 三个动作弹窗。
 * 合并是本域的"高危操作"：确认框里写清"from 会消失、其文章全部转挂 to"，
 * 并给出双方当前使用数——管理员看着数字做决策，比看 ID 靠谱得多。
 */
import { ref, reactive, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { fetchTags, createTag, updateTag, deleteTag, mergeTags } from '@/api/tag'

const tags = ref([])
const loading = ref(false)
const keyword = ref('')

/** 本地过滤（标签量级 = 治理对象，一次拉全比服务端分页省一套接口；<2000 枚不慌） */
const filtered = computed(() => {
  const k = keyword.value.trim().toLowerCase()
  return k ? tags.value.filter((t) => t.name.toLowerCase().includes(k)) : tags.value
})

const dialog = reactive({ visible: false, mode: 'create', id: null, form: {} })
const mergeDlg = reactive({ visible: false, fromTagId: null, toTagId: null })

async function load() {
  loading.value = true
  try {
    tags.value = await fetchTags()
  } finally {
    loading.value = false
  }
}
onMounted(load)

function openCreate() {
  Object.assign(dialog, { visible: true, mode: 'create', id: null, form: { name: '', color: '#409EFF' } })
}
function openEdit(row) {
  Object.assign(dialog, { visible: true, mode: 'edit', id: row.id, form: { name: row.name, color: row.color || '#409EFF' } })
}

async function submit() {
  const name = dialog.form.name?.trim()
  if (!name) {
    ElMessage.warning('标签名不能为空')
    return
  }
  if (/\s/.test(name)) {
    // 与服务端 @Pattern \S+ 同规则的前端先手：内部空白标签是"近义标签"重灾区
    ElMessage.warning('标签名不能包含空格')
    return
  }
  if (dialog.mode === 'create') {
    await createTag({ name, color: dialog.form.color })
    ElMessage.success('标签已创建')
  } else {
    await updateTag(dialog.id, { name, color: dialog.form.color })
    ElMessage.success('标签已更新')
  }
  dialog.visible = false
  load()
}

async function remove(row) {
  try {
    await ElMessageBox.confirm(
      `删除标签「${row.name}」（${row.articleCount ?? 0} 篇在用）？将同时解除它与所有文章的关联，文章本身不受影响。`,
      '删除确认', { type: 'warning' })
  } catch {
    return
  }
  await deleteTag(row.id)
  ElMessage.success('已删除')
  load()
}

/** 合并提交：成功文案带上目标标签的新使用数（数字反馈 = 让治理动作"看得见效果"） */
async function submitMerge() {
  if (!mergeDlg.fromTagId || !mergeDlg.toTagId) {
    ElMessage.warning('请选择源标签与目标标签')
    return
  }
  if (mergeDlg.fromTagId === mergeDlg.toTagId) {
    ElMessage.warning('不能与自身合并')   // 后端 400 兜底，前端先拦一次省一个来回
    return
  }
  const from = tags.value.find((t) => t.id === mergeDlg.fromTagId)
  const res = await mergeTags(mergeDlg.fromTagId, mergeDlg.toTagId)
  ElMessage.success(`已合并：「${from?.name}」的文章全部转挂，目标现共 ${res.articleCount} 篇`)
  mergeDlg.visible = false
  mergeDlg.fromTagId = mergeDlg.toTagId = null
  load()
}
</script>

<template>
  <div class="page">
    <div class="toolbar">
      <h2>标签管理</h2>
      <div class="actions">
        <el-input v-model="keyword" placeholder="筛选标签…" clearable style="width: 180px" />
        <el-button @click="mergeDlg.visible = true">合并标签</el-button>
        <el-button type="primary" @click="openCreate">新建标签</el-button>
      </div>
    </div>

    <el-table v-loading="loading" :data="filtered" row-key="id" style="max-width: 900px">
      <el-table-column label="标签" min-width="200">
        <template #default="{ row }">
          <el-tag :color="row.color" style="border: none; color: #fff">{{ row.name }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="色值" prop="color" width="110" />
      <el-table-column label="使用数" prop="articleCount" width="90" align="center" />
      <el-table-column label="操作" width="180" align="center">
        <template #default="{ row }">
          <el-button link size="small" @click="openEdit(row)">编辑</el-button>
          <el-button link size="small" type="danger" @click="remove(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="dialog.visible" :title="dialog.mode === 'create' ? '新建标签' : '编辑标签'" width="420px">
      <el-form label-width="72px">
        <el-form-item label="名称" required>
          <el-input v-model="dialog.form.name" maxlength="30" show-word-limit placeholder="≤30 字，不含空格（全站唯一）" />
        </el-form-item>
        <el-form-item label="颜色">
          <el-color-picker v-model="dialog.form.color" />
          <span class="hint">标签云展示色（#RRGGBB）</span>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialog.visible = false">取消</el-button>
        <el-button type="primary" @click="submit">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="mergeDlg.visible" title="合并标签" width="440px">
      <el-alert type="info" :closable="false" show-icon class="merge-tip">
        源标签的全部文章关联将转挂到目标标签，源标签随后被删除。重叠文章自动去重，不会重复计数。
      </el-alert>
      <el-form label-width="84px">
        <el-form-item label="源标签">
          <el-select v-model="mergeDlg.fromTagId" filterable placeholder="被合并（将消失）" style="width: 100%">
            <el-option v-for="t in tags" :key="t.id" :label="`${t.name}（${t.articleCount ?? 0}）`" :value="t.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="目标标签">
          <el-select v-model="mergeDlg.toTagId" filterable placeholder="保留并接管关联" style="width: 100%">
            <el-option v-for="t in tags" :key="t.id" :label="`${t.name}（${t.articleCount ?? 0}）`" :value="t.id" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="mergeDlg.visible = false">取消</el-button>
        <el-button type="primary" @click="submitMerge">确认合并</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.page { padding: 4px; }
.toolbar { display: flex; justify-content: space-between; align-items: center; margin-bottom: 14px; }
.toolbar h2 { font-size: 17px; margin: 0; }
.actions { display: flex; gap: 10px; }
.hint { margin-left: 8px; color: #909399; font-size: 12px; }
.merge-tip { margin-bottom: 14px; }
</style>
