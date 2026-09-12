<script setup>
/**
 * 后台-分类管理页（M4）—— UC-A-03：分类增删改查与排序。
 * 对接《API接口设计.md》§5 分类四接口；权限点 category:manage（仅 ADMIN 持有，
 * 后端 403/1005 由 Axios 拦截器统一 toast，页面只处理成功态——与用户管理页同约定）。
 *
 * 交互设计：
 * - 表格用 flattenCategoryTree 的"扁平带缩进"形态而不是 el-table 树模式：
 *   两级封顶的场景下，缩进前缀 + parentName 列足够表达层级，省一套 tree-props 心智；
 * - 删除按钮不做前端预检（前端永远数不准"有没有文章"——草稿列表分页看不全），
 *   直接把后端的 2101/2102 文案 toast 出去，错误即说明书。
 */
import { ref, reactive, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { fetchCategoryTree, createCategory, updateCategory, deleteCategory } from '@/api/category'
import { flattenCategoryTree } from '@/utils/tagCategory'

const tree = ref([])
const loading = ref(false)
const dialog = reactive({ visible: false, mode: 'create', id: null, form: {} })

/** 扁平展示行（含缩进 label / depth / articleCount），树数据的只读投影 */
const rows = computed(() => flattenCategoryTree(tree.value))

/** 新建子分类的下拉候选：只有"尚未挂到别人的顶级分类"能当父（两级封顶的前端投影，后端仍会 400/2102 兜底） */
const parentOptions = computed(() =>
  tree.value.filter((n) => !n.parentId || n.parentId === 0))

async function load() {
  loading.value = true
  try {
    tree.value = await fetchCategoryTree()
  } finally {
    loading.value = false
  }
}
onMounted(load)

function openCreate(parentId = 0) {
  Object.assign(dialog, {
    visible: true, mode: 'create', id: null,
    form: { name: '', slug: '', parentId, sortOrder: 0, description: '' }
  })
}
function openEdit(row) {
  Object.assign(dialog, {
    visible: true, mode: 'edit', id: row.id,
    // 只回填可编辑列；articleCount 是系统维护的冗余值，不开放手改。
    // 数据源是 _raw（decorate 挂回的原始树节点）——扁平投影刻意只带展示字段
    form: {
      name: row.name,
      parentId: row._raw?.parentId ?? 0,
      sortOrder: row._raw?.sortOrder ?? 0,
      description: row._raw?.description || ''
    }
  })
}

/** 提交：create/edit 共用；后端 null=不变更，这里全量回传表单即可（编辑页数据量小） */
async function submit() {
  const body = {
    name: dialog.form.name?.trim(),
    parentId: dialog.form.parentId || 0,
    sortOrder: Number(dialog.form.sortOrder) || 0,
    description: dialog.form.description || null
  }
  if (!body.name) {
    ElMessage.warning('分类名不能为空')
    return
  }
  if (dialog.mode === 'create') {
    await createCategory(body)
    ElMessage.success('分类已创建')
  } else {
    await updateCategory(dialog.id, body)
    ElMessage.success('分类已更新')
  }
  dialog.visible = false
  load()
}

/** 删除：确认框讲清"有文章会被拒"，避免管理员把 2101 当系统坏了来报障 */
async function remove(row) {
  try {
    await ElMessageBox.confirm(
      `删除分类「${row.name}」？分类下仍有文章（含草稿）或子分类时会被服务端拒绝。`,
      '删除确认', { type: 'warning' })
  } catch {
    return
  }
  await deleteCategory(row.id)
  ElMessage.success('已删除')
  load()
}

/** 给行补原始节点引用（编辑时需要 parentId/description 等扁平投影没带的列） */
function decorate(flat) {
  const byId = new Map()
  const walk = (nodes) => nodes.forEach((n) => { byId.set(n.id, n); walk(n.children || []) })
  walk(tree.value)
  return flat.map((f) => ({ ...f, _raw: byId.get(f.id) }))
}
const displayRows = computed(() => decorate(rows.value))
</script>

<template>
  <div class="page">
    <div class="toolbar">
      <h2>分类管理</h2>
      <el-button type="primary" @click="openCreate(0)">新建顶级分类</el-button>
    </div>

    <el-table v-loading="loading" :data="displayRows" row-key="id" style="max-width: 900px">
      <el-table-column label="名称" prop="label" min-width="200" />
      <el-table-column label="文章数" prop="articleCount" width="90" align="center" />
      <el-table-column label="排序" prop="sortOrder" width="80" align="center">
        <template #default="{ row }">{{ row._raw?.sortOrder }}</template>
      </el-table-column>
      <el-table-column label="操作" width="220" align="center">
        <template #default="{ row }">
          <el-button v-if="!row._raw?.parentId || row._raw.parentId === 0" link size="small"
                     @click="openCreate(row.id)">加子分类</el-button>
          <el-button link size="small" @click="openEdit(row)">编辑</el-button>
          <el-button link size="small" type="danger" @click="remove(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="dialog.visible" :title="dialog.mode === 'create' ? '新建分类' : '编辑分类'"
               width="460px">
      <el-form label-width="80px">
        <el-form-item label="名称" required>
          <el-input v-model="dialog.form.name" maxlength="50" show-word-limit placeholder="分类名（全站唯一）" />
        </el-form-item>
        <el-form-item label="父分类">
          <el-select v-model="dialog.form.parentId" style="width: 100%">
            <el-option label="（顶级）" :value="0" />
            <!-- 两级封顶：候选只列顶级；编辑时排除自己（后端 400 兜底，前端先禁） -->
            <el-option v-for="p in parentOptions.filter((x) => x.id !== dialog.id)" :key="p.id"
                       :label="p.name" :value="p.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="排序">
          <el-input-number v-model="dialog.form.sortOrder" :min="0" />
          <span class="hint">越小越靠前</span>
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="dialog.form.description" type="textarea" :rows="2" maxlength="200" show-word-limit />
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
.page { padding: 4px; }
.toolbar { display: flex; justify-content: space-between; align-items: center; margin-bottom: 14px; }
.toolbar h2 { font-size: 17px; margin: 0; }
.hint { margin-left: 8px; color: #909399; font-size: 12px; }
</style>
