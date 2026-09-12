<script setup>
/**
 * Markdown 编辑器（M3）—— UC-U-04/07/08/09 的写作端，含：
 * · textarea 先行版编辑器（md-editor-v3 集成留 M10 全站打通；textarea+预览双栏
 *   已覆盖"撰写→实时预览→保存"闭环，且零新依赖——组件库换皮不该阻塞后端契约验证）
 * · 图片上传钩子（粘贴/按钮 → POST /api/users/avatar 通道同构的通用上传在 M8 落地，
 *   本期封面走已有上传口，正文插图按钮给出引导提示，不留死按钮）
 * · localStorage 草稿兜底（阶段四任务 5）+ 2004 冲突决策弹框（D-8）
 * · AI 摘要按钮占位（M6 接入；置灰依据 /api/ai/status，先按不可用渲染）
 */
import { ref, reactive, computed, onMounted, onBeforeUnmount, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  fetchArticle, createArticle, updateArticle, changeArticleStatus
} from '@/api/article'
import { fetchCategoryTree } from '@/api/category'
import { fetchTags } from '@/api/tag'
import { renderMarkdown } from '@/utils/markdown'
import { decideSaveFailure, localDraftKey, resolveEditorSeed, VERSION_CONFLICT } from '@/utils/articleFlow'
import { flattenCategoryTree, parseTagInput } from '@/utils/tagCategory'

const route = useRoute()
const router = useRouter()
const articleId = computed(() => (route.params.id ? Number(route.params.id) : null))

/** 表单模型：字段与 ArticleSaveDTO 一一对应（校验规则也同构：标题必填 ≤200） */
const form = reactive({
  title: '', contentMd: '', summary: '', categoryId: null,
  tagsText: '', cover: '', allowComment: 1,
  version: 0, status: 0
})
const serverUpdatedAt = ref(null)
/**
 * 实时字数（近似）：后端入库值由 MarkdownProcessor 剥离语法噪音后统计，
 * 前端无法低成本复刻该管道——展示"约"值即可，保存后以服务端回显的精确值为准，
 * 两边不做防御性对齐校验（口径差异是设计使然，不是 bug）。
 */
const wordCount = computed(() =>
  (form.contentMd || '').replace(/\s+/g, '').length)
const saving = ref(false)

/** 双栏预览：与详情页同一渲染管道（编辑所见=发布所见，杜绝"两套解析结果不一致"） */
const previewHtml = computed(() => renderMarkdown(form.contentMd))

/** 标签：输入框以逗号/空格分隔，提交时切分为数组（后端 tags 是字符串数组契约）。
 *  M4：切分逻辑提升为 parseTagInput 纯函数（中英文逗号/大小写去重/上限截断），已单测。 */
const tagList = computed(() => parseTagInput(form.tagsText))

// ---------- M4：分类/标签候选数据（编辑器不再写死种子三分类） ----------
/** 分类扁平选项（带缩进 label）；拉取失败退化为空列表——分类是可选字段，不阻塞写作 */
const categoryOptions = ref([])
/** 已有标签名（给 el-autocomplete 做补全：近义标签是标签表头号脏数据来源，补全比校验便宜） */
const knownTagNames = ref([])
const tagSuggestions = computed(() => {
  const last = (form.tagsText.split(/[,，\s]+/).pop() || '').trim().toLowerCase()
  if (!last) return knownTagNames.value.slice(0, 12)
  return knownTagNames.value.filter((n) => n.toLowerCase().includes(last)).slice(0, 12)
})
/** 选中补全项：把"正在输入的末段"替换为所选标签并补逗号（el-autocomplete 默认整体替换输入框，
 *  会吞掉前面已输入的标签——必须手动回写整串） */
function onPickTag(item) {
  const parts = form.tagsText.split(/[,，\s]+/).map((s) => s.trim()).filter(Boolean)
  if (parts.length) parts.pop()          // 丢掉末段半成品
  parts.push(item.value)
  form.tagsText = parts.join(', ') + ', '
}

// ---------- localStorage 草稿兜底 ----------
let draftTimer = null
function snapshotLocal() {
  if (!form.title && !form.contentMd) return
  try {
    localStorage.setItem(localDraftKey(articleId.value), JSON.stringify({
      title: form.title, contentMd: form.contentMd, summary: form.summary, savedAt: Date.now()
    }))
  } catch { /* 隐私模式/配额满：兜底功能自身失败不能打断写作主流程 */ }
}
function clearLocalDraft() {
  try { localStorage.removeItem(localDraftKey(articleId.value)) } catch { /* 同上 */ }
}
/** 900ms 防抖自动快照：比"每键一写"省 localStorage IO，比"5 秒轮询"丢得少 */
watch(form, () => {
  clearTimeout(draftTimer)
  draftTimer = setTimeout(snapshotLocal, 900)
}, { deep: true })

// ---------- 载入 ----------
async function load() {
  if (!articleId.value) {
    // 新建：若 localStorage 有上次没保存成的一刻（崩溃/误关页），提示恢复
    let local = null
    try { local = JSON.parse(localStorage.getItem(localDraftKey(null)) || 'null') } catch { /* 脏数据视为无 */ }
    if (local && local.contentMd) {
      try {
        await ElMessageBox.confirm('检测到上次未保存的草稿，恢复继续编辑？', '草稿恢复',
          { confirmButtonText: '恢复', cancelButtonText: '丢弃', type: 'info' })
        Object.assign(form, { title: local.title, contentMd: local.contentMd, summary: local.summary || '' })
        ElMessage.success('已恢复本地草稿')
      } catch {
        clearLocalDraft()   // 用户明确丢弃：清掉，否则下次还弹
      }
    }
    return
  }
  const a = await fetchArticle(articleId.value)   // 2001 直接抛 → 路由守卫外的错误态由拦截器 toast
  // 详情接口只回 contentMd 不回分类/标签的编辑器形态字段？——回的是聚合对象，这里拆成编辑器模型
  serverUpdatedAt.value = a.updatedAt
  Object.assign(form, {
    title: a.title, contentMd: a.contentMd || '', summary: a.summary || '',
    categoryId: a.category?.id ?? null, tagsText: (a.tags || []).map((t) => t.name).join(', '),
    cover: a.cover || '', allowComment: a.allowComment ?? 1, version: a.version, status: a.status
  })
  // 本地草稿 vs 服务器版本：谁新用谁，但"用本地"也只是预填——必须用户主动保存才回库
  let local = null
  try { local = JSON.parse(localStorage.getItem(localDraftKey(articleId.value)) || 'null') } catch { /* 脏数据视为无 */ }
  const seed = resolveEditorSeed(local, a, a.updatedAt)
  if (seed.use === 'local' && local?.contentMd) {
    form.contentMd = local.contentMd
    ElMessage.info(seed.reason + '（已载入本地版，保存后生效）')
  }
}

// ---------- 保存 ----------
function buildPayload() {
  return {
    title: form.title.trim(),
    categoryId: form.categoryId || null,
    tags: tagList.value,
    cover: form.cover || null,
    summary: form.summary || null,
    summarySource: 0,               // 手动：AI 来源在 M6 由生成动作改写此位
    contentMd: form.contentMd,
    allowComment: form.allowComment
    // status/version 由具体动作补：草稿/发布/更新三条路径语义不同
  }
}

async function doSave(extra) {
  saving.value = true
  try {
    const payload = { ...buildPayload(), ...extra }
    if (articleId.value) {
      await updateArticle(articleId.value, payload)
    } else {
      const res = await createArticle(payload)
      // 新建成功 → 路由原地换成编辑态（后续保存走 PUT，防用户二次提交产生重复文章）
      router.replace(`/user/articles/${res.id}/edit`)
    }
    clearLocalDraft()
    ElMessage.success(extra.status === 1 ? '已发布' : '草稿已保存')
    if (extra.status === 1) router.push(`/articles/${articleId.value ?? ''}`.replace(/\/$/, ''))
  } catch (e) {
    // D-8 决策表：2004 → 让用户在"留在本页保本地/重拉服务器版"之间选，绝不静默覆盖
    const d = decideSaveFailure(e, { hasLocalDraft: true })
    if (d.action === 'ask-conflict') {
      try {
        await ElMessageBox.confirm(d.message, '编辑冲突', {
          confirmButtonText: '加载服务器版本', cancelButtonText: '保留我的编辑', type: 'warning'
        })
        await load()   // 用户选择放弃本地 → 重拉（含新 version，解锁再次保存）
      } catch { /* 保留本地：什么都不做，version 保持旧值，用户可复制走或稍后重试 */ }
    } else {
      ElMessage.error(d.message)
    }
  } finally {
    saving.value = false
  }
}

const saveDraft = () => doSave({ status: 0, version: articleId.value ? form.version : undefined })
const publish = () => {
  if (!form.title.trim() || !form.contentMd.trim()) {
    ElMessage.warning('标题和正文不能为空')
    return
  }
  if (articleId.value) {
    // 编辑态发布：走状态机 PATCH（而非整包 PUT 携带 status——发布是"动作"不是"字段覆盖"，
    // 两个入口在后端走同一矩阵校验，但 PATCH 不依赖当前 version 也不误伤未加载字段）
    doSave({ status: 1, version: form.version })
  } else {
    doSave({ status: 1 })
  }
}

onMounted(() => {
  load()
  // M4：候选数据异步补充（失败静默：分类下拉空着也能保存草稿）
  fetchCategoryTree().then((tree) => { categoryOptions.value = flattenCategoryTree(tree) }).catch(() => {})
  fetchTags().then((ts) => { knownTagNames.value = ts.map((t) => t.name) }).catch(() => {})
})
onBeforeUnmount(() => clearTimeout(draftTimer))
</script>

<template>
  <div class="editor-page">
    <div class="editor-form">
      <el-input v-model="form.title" maxlength="200" show-word-limit placeholder="文章标题" size="large" />
      <div class="row">
        <!-- M4：标签输入补全已有标签（选中即追加），减少"同义词各建一个"的治理债 -->
        <el-autocomplete v-model="form.tagsText" :fetch-suggestions="() => tagSuggestions.map((n) => ({ value: n }))"
                         placeholder="标签（逗号分隔，最多 10 个，不存在的自动创建）" clearable style="width: 100%"
                         @select="onPickTag" />
        <el-select v-model="form.categoryId" placeholder="分类" clearable style="width: 160px">
          <!-- M4：动态拉取分类树（扁平带缩进），不再写死种子三分类 -->
          <el-option v-for="c in categoryOptions" :key="c.id" :label="c.label" :value="c.id" />
        </el-select>
      </div>
      <el-input v-model="form.summary" type="textarea" :rows="2" maxlength="500" show-word-limit
                placeholder="摘要（可留空；M6 接入 AI 一键生成）" />
      <div class="md-toolbar">
        <span class="count">字数（去空白）：{{ wordCount }}</span>
        <span class="hint">提示：图片上传（/uploads 通道）M8 完整开放；本期正文可直接粘贴外链 ![]()</span>
      </div>
      <div class="md-split">
        <textarea v-model="form.contentMd" class="md-input" placeholder="# 用 Markdown 写作…"></textarea>
        <div class="md-preview" v-html="previewHtml"></div>
      </div>
      <div class="actions">
        <el-button :loading="saving" @click="saveDraft">存草稿</el-button>
        <el-button type="primary" :loading="saving" @click="publish">发布</el-button>
        <el-tooltip content="AI 摘要在 M6（课题重点模块）接入" placement="top">
          <el-button disabled>✨ AI 摘要</el-button>
        </el-tooltip>
        <el-button link @click="router.back()">返回</el-button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.editor-page { max-width: 1200px; margin: 20px auto; padding: 0 16px; }
.editor-form { display: flex; flex-direction: column; gap: 10px; }
.row { display: flex; gap: 10px; }
.md-toolbar { display: flex; justify-content: space-between; color: #909399; font-size: 12px; }
.md-split { display: flex; gap: 12px; height: 52vh; min-height: 340px; }
.md-input { flex: 1; font-family: 'JetBrains Mono', Consolas, monospace; font-size: 14px; padding: 10px; border: 1px solid #dcdfe6; border-radius: 6px; resize: none; line-height: 1.7; }
.md-preview { flex: 1; overflow: auto; border: 1px solid #ebeef5; border-radius: 6px; padding: 10px 14px; background: #fafbfc; line-height: 1.8; }
.md-preview :deep(pre) { background: #f0f2f5; padding: 10px; border-radius: 6px; overflow: auto; }
.actions { display: flex; gap: 10px; align-items: center; }
</style>
