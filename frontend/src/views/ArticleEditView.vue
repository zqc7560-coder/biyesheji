<script setup>
/**
 * Markdown 编辑器（M10 换装版）—— UC-U-04/07/08/09 的写作端。
 * · md-editor-v3 集成（M3/M4 台账顺延的换装项在此闭环）：自带工具栏/双栏预览/
 *   快捷键；onUploadImg 钩子把"图片按钮"接到 M8 通用上传通道（三卡校验在后端）。
 *   注意：编辑器预览用 md-editor 自带渲染器（写作辅助视图），详情页仍走 M3 的
 *   renderMarkdown 三道闸管道（markdown-it html:false + 协议白名单 + DOMPurify）——
 *   安全口径以详情页为准，编辑器预览不是渲染契约的一部分。
 * · localStorage 草稿兜底（阶段四任务 5）+ 2004 冲突决策弹框（D-8）
 * · AI 摘要/标签（M6/M7 落地）：置灰依据 /api/ai/status；标签走勾选确认弹窗
 * · 封面图上传（M8）保留自研按钮（表单字段而非正文插入，不走编辑器钩子）
 */
import { ref, reactive, computed, onMounted, onBeforeUnmount, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { MdEditor } from 'md-editor-v3'
import 'md-editor-v3/lib/style.css'
import {
  fetchArticle, createArticle, updateArticle, changeArticleStatus
} from '@/api/article'
import { fetchCategoryTree } from '@/api/category'
import { fetchTags } from '@/api/tag'
import { decideSaveFailure, localDraftKey, resolveEditorSeed, VERSION_CONFLICT } from '@/utils/articleFlow'
import { flattenCategoryTree, parseTagInput } from '@/utils/tagCategory'
// M6：AI 摘要（课题重点）——按钮态探测 + 生成回填 + 降级处置
// M7：AI 标签候选（勾选确认制）+ 批量入口不在编辑器（那是 ADMIN 治理页的动作）
import { aiSummary, aiTags, aiStatus } from '@/api/ai'
// M8→M10：封面图上传（正文插图换装后走 MdEditor 的 onUploadImg 钩子，同函数复用）
import { uploadImage } from '@/api/file'
import { aiButtonState, summaryFailureAction, summaryBackfillHint,
         mergeAiTagCandidates, appendTagsToText } from '@/utils/ai'
import { useUserStore } from '@/stores/user'
// M15：断点单一事实源（utils/responsive.js）——编辑器窄屏降级判定的输入。
// 《部署与测试.md》W13 口径"编辑器移动端断点降级"：双栏预览在手机上各占
// 不足 375px 宽（代码块横向滚动+文字折行，两头都不可读），窄屏关预览列
// 保编辑列——工具栏/输入全功能保留（手机上存草稿改错字是真实场景，
// "只读"一刀切会把写作入口焊死，是对 W13 口径的落地细化）。
import { isMobileViewport, MOBILE_MAX } from '@/utils/responsive'

/** 视口态响应式跟踪：matchMedia 比 resize 节流精确（只在跨断点沿触发） */
const mobileView = ref(isMobileViewport())
if (typeof window !== 'undefined' && window.matchMedia) {
  const mq = window.matchMedia(`(max-width: ${MOBILE_MAX}px)`)
  const onMq = (e) => { mobileView.value = e.matches }
  mq.addEventListener ? mq.addEventListener('change', onMq) : mq.addListener(onMq)
  onBeforeUnmount(() => (mq.removeEventListener ? mq.removeEventListener('change', onMq) : mq.removeListener(onMq)))
}

const userStore = useUserStore()

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

// M10 换装说明：双栏预览改由 MdEditor 内置渲染器承担（写作辅助视图）；
// 详情页的 renderMarkdown 三道闸管道（安全口径）不变——编辑器预览不是渲染契约。

/** 标签：输入框以逗号/空格分隔，提交时切分为数组（后端 tags 是字符串数组契约）。
 *  M4：切分逻辑提升为 parseTagInput 纯函数（中英文逗号/大小写去重/上限截断），已单测。 */
const tagList = computed(() => parseTagInput(form.tagsText))

// ---------- M6：AI 摘要 ----------
/** /api/ai/status 快照（null=探测未回来，按钮先灰防"点了才发现没配"） */
const aiInfo = ref(null)
const aiLoading = ref(false)
/** 摘要来源位：0 手动 / 1 AI。AI 回填置 1；用户随后手动改摘要即回落 0
 *  （"AI 生成"角标只对"未经人工改写的 AI 产物"负责——合规标识的语义边界） */
const summarySource = ref(0)
let aiBackfilling = false   // 区分"AI 回填触发的 watch"与"用户手动编辑触发的 watch"

const aiBtn = computed(() => aiButtonState(aiInfo.value, userStore.isLoggedIn))

async function probeAi() {
  try { aiInfo.value = await aiStatus() } catch { aiInfo.value = null }
}

async function runAiSummary() {
  if (!form.contentMd?.trim()) {
    ElMessage.warning('先写点正文，AI 才有东西可总结')
    return
  }
  aiLoading.value = true
  try {
    const r = await aiSummary({ title: form.title || undefined, contentMd: form.contentMd })
    aiBackfilling = true
    form.summary = r.summary
    summarySource.value = 1
    ElMessage.success('AI 摘要已回填，可继续修改')
    const hint = summaryBackfillHint(r)
    if (hint) ElMessage.info(hint)
  } catch (e) {
    const act = summaryFailureAction(e)
    ElMessage.warning(act.message)
    if (act.action === 'disable') probeAi()   // 未配置/被关闭：重探状态让按钮立刻变灰，别让用户反复碰壁
  } finally {
    aiLoading.value = false
  }
}

watch(() => form.summary, (v, old) => {
  // AI 回填自身不改来源位；用户改过（无论在手输还是回填后）即视为人工摘要
  if (!aiBackfilling && v !== old && summarySource.value === 1) summarySource.value = 0
  aiBackfilling = false
})

// ---------- M7：AI 标签（候选→勾选→追加，确认制红线） ----------
const tagLoading = ref(false)
/** AI 候选弹窗：visible + 候选清单（mergeAiTagCandidates 产物） */
const tagDialog = reactive({ visible: false, candidates: [] })
const tagPicked = ref([])
/** 已入库标签的 id↔name 字典（existingTagIds 回查映射用；与补全候选同源数据） */
const knownTagDict = ref([])

async function runAiTags() {
  if (!form.contentMd?.trim()) {
    ElMessage.warning('先写点正文，AI 才有东西可提炼')
    return
  }
  tagLoading.value = true
  try {
    const r = await aiTags({ title: form.title || undefined, contentMd: form.contentMd })
    // 确认制（AI 模块设计 §5.2 红线）：不直接写输入框——弹窗让用户勾选后才追加。
    // 摘要敢直写是因为只有一个字段且用户看得见全文；标签一旦进输入框就会被
    // parseTagInput 静默拆分入库，"AI 塞了什么用户没来得及看"正是标签治理债的来源。
    const merged = mergeAiTagCandidates(r.tags, r.existingTagIds, knownTagDict.value, tagList.value)
    if (!merged.length) {
      ElMessage.info('AI 给出的候选与你已有标签完全重合，无需追加')
      return
    }
    tagDialog.candidates = merged
    tagPicked.value = merged.map((c) => c.name)   // 默认全选：用户可以"减法式"确认，比逐个勾快
    tagDialog.visible = true
    if (r.fromCache) ElMessage.info('命中缓存（内容与上次生成时一致，未消耗新额度）')
  } catch (e) {
    const act = summaryFailureAction(e)           // 降级处置与摘要同族（4001~4005 语义一致）
    ElMessage.warning(act.message)
    if (act.action === 'disable') probeAi()
  } finally {
    tagLoading.value = false
  }
}

/** 弹窗确认：勾中的候选追加进 tagsText（去重与格式由 appendTagsToText 纯函数负责） */
function confirmAiTags() {
  form.tagsText = appendTagsToText(form.tagsText, tagPicked.value)
  tagDialog.visible = false
  ElMessage.success(`已追加 ${tagPicked.value.length} 个标签，保存文章后生效`)
}

// ---------- M8→M10：封面图上传（自研按钮保留）/ 正文插图（换装后走编辑器钩子） ----------
const coverUploading = ref(false)

/** 封面：选文件 → uploadImage 预检+上传 → 回写 form.cover（表单字段，不进正文） */
async function handlePickCover(ev) {
  const file = ev.target.files?.[0]
  ev.target.value = ''   // 立即清空：连续选同一张图也能再次触发 change（浏览器按值去重）
  if (!file) return
  coverUploading.value = true
  try {
    const r = await uploadImage(file, 'cover')
    form.cover = r.url
    ElMessage.success('封面已设置，保存文章后生效')
  } catch (e) {
    ElMessage.error(e.message || '图片上传失败')
  } finally {
    coverUploading.value = false
  }
}

/**
 * md-editor-v3 图片上传钩子：工具栏"图片"按钮/拖拽/粘贴传进来的 File[]，
 * 逐个走 M8 通用通道（前端预检 + 后端三卡校验），回调解码后组件自行在光标处
 * 插入 ![]()。单张失败不拖垮整批（toast 后继续），部分成功仍是成功——与
 * batchEnrich"单败不拖全批"的仓库口径一致。
 */
async function onUploadImg(files, callback) {
  const urls = []
  for (const file of Array.from(files)) {
    try {
      const r = await uploadImage(file, 'article')
      urls.push(r.url)
    } catch (e) {
      ElMessage.error(e.message || `《${file.name}》上传失败`)
    }
  }
  callback(urls)
}

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
  summarySource.value = a.summarySource ?? 0   // M6：编辑已有文章时继承库里的摘要来源位
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
    summarySource: summarySource.value,   // M6 落地：AI 回填置 1，用户手改回落 0（见上方 watch）
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
  fetchTags().then((ts) => {
    knownTagNames.value = ts.map((t) => t.name)
    knownTagDict.value = ts.map((t) => ({ id: t.id, name: t.name }))   // M7：AI 标签"已有"角标的 id↔name 字典
  }).catch(() => {})
  probeAi()   // M6：AI 按钮态探测（公开接口，失败静默——探测挂了就当 AI 不可用）
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
                placeholder="摘要（可留空；点下方 AI 摘要一键生成）" />
      <!-- M8：封面图（URL 直填或上传回填；列表页卡片图来源） -->
      <div class="cover-row">
        <el-input v-model="form.cover" placeholder="封面图 URL（可留空；右侧按钮上传后自动回填）" clearable style="max-width:480px" />
        <label class="upload-btn">
          <input type="file" accept=".png,.jpg,.jpeg,.webp,.gif" hidden @change="handlePickCover" />
          <el-button size="small" :loading="coverUploading">上传封面</el-button>
        </label>
        <img v-if="form.cover" :src="form.cover" class="cover-prev" alt="封面预览" />
      </div>
      <div class="md-toolbar">
        <span class="count">字数（去空白）：{{ wordCount }}</span>
        <span class="hint">M10：正文插图改用编辑器工具栏图片按钮（点击/拖拽/粘贴均可，走同一上传通道）</span>
      </div>
      <!-- M10 换装：md-editor-v3（工具栏+双栏预览+快捷键，onUploadImg 接 M8 通道）；
           editorId 固定值防同页多实例 id 冲突。preview 默认开（等分双栏）；
           M15：窄屏 preview=false——双栏在手机上是"两栏都不可读"，编辑列全宽优先 -->
      <MdEditor v-model="form.contentMd" editor-id="blog-editor" :toolbars-exclude="['github','save']"
                :onUploadImg="onUploadImg" placeholder="# 用 Markdown 写作…" :preview="!mobileView"
                style="height: 56vh; min-height: 380px" />
      <div class="actions">
        <el-button :loading="saving" @click="saveDraft">存草稿</el-button>
        <el-button type="primary" :loading="saving" @click="publish">发布</el-button>
        <!-- M6：AI 摘要按钮——置灰依据 /api/ai/status 四态（关闭/未配置/未登录/额度尽），
             tooltip 说清"为什么不能点"；可用态下带 loading（异步端点最长 35s） -->
        <el-tooltip :content="aiBtn.disabled ? aiBtn.reason : '用默认模型生成摘要并回填（AI 结果仍需人工确认）'"
                    placement="top">
          <span>
            <el-button :disabled="aiBtn.disabled" :loading="aiLoading" @click="runAiSummary">✨ AI 摘要</el-button>
          </span>
        </el-tooltip>
        <!-- M7：AI 标签按钮——同款置灰逻辑（aiButtonState 单一事实源）；产出走勾选弹窗而非直写 -->
        <el-tooltip :content="aiBtn.disabled ? aiBtn.reason : '用默认模型提炼候选标签（勾选后追加，保存时生效）'"
                    placement="top">
          <span>
            <el-button :disabled="aiBtn.disabled" :loading="tagLoading" @click="runAiTags">🏷 AI 标签</el-button>
          </span>
        </el-tooltip>
        <el-tag v-if="summarySource === 1" size="small" type="primary" effect="plain">摘要来源：AI</el-tag>
        <el-button link @click="router.back()">返回</el-button>
      </div>
    </div>

    <!-- M7：AI 标签候选确认弹窗（§5.2"结果仅回显、用户确认后入库"红线的前端落点）。
         默认全选=减法式确认；"已有"角标来自后端 existingTagIds × 前端 id↔name 字典 -->
    <el-dialog v-model="tagDialog.visible" title="AI 提炼的候选标签（勾选要采纳的）" width="420px">
      <el-checkbox-group v-model="tagPicked">
        <div v-for="c in tagDialog.candidates" :key="c.name" class="tag-cand">
          <el-checkbox :value="c.name">
            {{ c.name }}
            <el-tag v-if="c.existing" size="small" type="success" effect="plain">已有</el-tag>
          </el-checkbox>
        </div>
      </el-checkbox-group>
      <template #footer>
        <el-button @click="tagDialog.visible = false">取消</el-button>
        <el-button type="primary" :disabled="!tagPicked.length" @click="confirmAiTags">
          追加 {{ tagPicked.length }} 个标签
        </el-button>
      </template>
    </el-dialog>
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
.tag-cand { padding: 4px 0; }
/* M8：封面行与上传按钮 */
.cover-row { display: flex; align-items: center; gap: 10px; }
.upload-btn { cursor: pointer; }
.cover-prev { height: 40px; max-width: 120px; object-fit: cover; border: 1px solid #dcdfe6; border-radius: 4px; }
/* M15 移动端断点系统回归：标签+分类行换行堆叠（160px 下拉在手机挤没）、
   动作按钮行包行（AI 双按钮+存草稿+发布一行放不下五个按钮是窄屏重灾区）、
   换装提示纯文案在窄屏让位给屏幕 */
@media (max-width: 768px) {
  .editor-page { margin: 12px auto; }
  .row { flex-wrap: wrap; }
  .row .el-select { width: 100%; }
  .actions { flex-wrap: wrap; }
  .cover-row { flex-wrap: wrap; }
  .md-toolbar .hint { display: none; }
}
</style>
