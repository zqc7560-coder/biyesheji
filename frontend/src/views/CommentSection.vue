<script setup>
/**
 * 文章评论区（M5）—— UC-V-03 查看 + UC-U-10/11 发表/删除的两级组件。
 *
 * 安全口径（NFR-05）：评论正文永远用 {{ }} 文本插值渲染，绝不 v-html——
 * 后端已 jsoup 清洗（第一道闸），前端不二次解析是第二道闸的另一半。
 *
 * 发表框三态：游客见"登录后参与讨论"占位（点击跳登录带 redirect 回跳）；
 * 作者关闭评论（allowComment=0）显示提示而非禁用框（语义差异给用户明确解释）；
 * 审核开关由服务端裁决——前端不猜 pending 与否，按响应 data.pending 分支提示。
 *
 * 回复交互：点"回复"把 @昵称 填进输入框顶部上下文条（可取消），parentId
 * 随提交带上；两级封顶是服务端执法（2302），前端只是不再给回复渲染"回复"按钮。
 */
import { ref, computed, watch } from 'vue'
import { useRouter } from 'vue-router'
import { useUserStore } from '@/stores/user'
import { fetchArticleComments, postComment, deleteComment } from '@/api/comment'
import { sortCommentTree, relativeTime, commentLengthCheck, COMMENT_MAX_LENGTH } from '@/utils/comment'
import { ElMessage, ElMessageBox } from 'element-plus'

const props = defineProps({
  /** 文章对象（用其 id/allowComment/userId 字段；详情接口已带） */
  article: { type: Object, required: true }
})

const router = useRouter()
const store = useUserStore()

const comments = ref([])
const loading = ref(false)
const draft = ref('')
/** 回复上下文：{parentId, nickname}；null=发顶级评论 */
const replyingTo = ref(null)

const canComment = computed(() => props.article.allowComment !== 0)
const lengthOk = computed(() => commentLengthCheck(draft.value))

async function load() {
  loading.value = true
  try {
    const page = await fetchArticleComments(props.article.id)
    comments.value = sortCommentTree(page.list ?? [])
  } catch {
    comments.value = [] // 文章不可见等场景：评论区静默降级，主文体的错误由详情层提示
  } finally {
    loading.value = false
  }
}
watch(() => props.article.id, load, { immediate: true })

function startReply(top, reply = null) {
  if (!store.isLoggedIn) {
    router.push({ path: '/login', query: { redirect: router.currentRoute.value.fullPath } })
    return
  }
  // 回复"回复"仍挂顶级（两级封顶的 UI 投影）：target 是被@的人，parentId 恒为顶级 id
  replyingTo.value = { parentId: top.id, nickname: (reply ?? top).user?.nickname ?? '用户' }
}

async function submit() {
  if (!store.isLoggedIn) {
    router.push({ path: '/login', query: { redirect: router.currentRoute.value.fullPath } })
    return
  }
  if (!lengthOk.value.ok) {
    ElMessage.warning(lengthOk.value.over ? `评论不能超过 ${COMMENT_MAX_LENGTH} 字` : '评论内容不能为空')
    return
  }
  const body = { articleId: props.article.id, content: draft.value.trim() }
  if (replyingTo.value) {
    body.parentId = replyingTo.value.parentId
    // replyToUid 不带：后端缺省自动@父评论作者，多传反而给用户"可选择"的错觉
  }
  const data = await postComment(body)
  draft.value = ''
  replyingTo.value = null
  if (data.pending) {
    ElMessage.success('评论已提交，等待审核')
  } else {
    ElMessage.success('评论发表成功')
    load() // 直发模式增量插 DOM 会漏嵌套装配细节，重拉一次与后端事实对齐
  }
}

async function remove(c) {
  try {
    await ElMessageBox.confirm('删除后不可恢复（其下回复会一并移除），确定删除？', '删除评论', { type: 'warning' })
  } catch {
    return
  }
  await deleteComment(c.id)
  ElMessage.success('已删除')
  load()
}

/** 可见删除口：本人或 ADMIN（后端逐请求鉴权，这里只藏入口不当地闸） */
function canDelete(c) {
  return store.user && (store.user.id === c.user?.id || store.isAdmin)
}
</script>

<template>
  <section class="comment-zone" v-loading="loading">
    <h3>评论 {{ comments.length ? `(${article.commentCount ?? comments.length})` : '' }}</h3>

    <!-- 发表框：三态之一——关闭评论 / 游客占位 / 正常输入 -->
    <div v-if="!canComment" class="closed-tip">作者已关闭本文评论</div>
    <div v-else class="composer">
      <div v-if="!store.isLoggedIn" class="guest-tip">
        <a @click="router.push({ path: '/login', query: { redirect: $route.fullPath } })">登录</a>
        后参与讨论
      </div>
      <template v-else>
        <div v-if="replyingTo" class="reply-bar">
          正在回复 <b>{{ replyingTo.nickname }}</b>
          <el-button link size="small" @click="replyingTo = null">取消</el-button>
        </div>
        <el-input v-model="draft" type="textarea" :rows="3" resize="none"
                  :maxlength="COMMENT_MAX_LENGTH + 50" show-word-limit
                  placeholder="友善表达，纯文本（不支持链接与格式）" />
        <div class="composer-foot">
          <span :class="{ over: lengthOk.over }">{{ lengthOk.len }}/{{ COMMENT_MAX_LENGTH }}</span>
          <el-button type="primary" size="small" :disabled="!lengthOk.ok" @click="submit">
            {{ replyingTo ? '发表回复' : '发表评论' }}
          </el-button>
        </div>
      </template>
    </div>

    <!-- 两级列表：内容一律文本插值（XSS 前端半闸）；时间悬浮给绝对值 -->
    <ul class="comment-list">
      <li v-for="top in comments" :key="top.id" class="top-item">
        <div class="c-row">
          <span class="who">{{ top.user?.nickname ?? '已注销用户' }}</span>
          <span class="when" :title="top.createdAt">{{ relativeTime(top.createdAt) }}</span>
          <div class="c-body">{{ top.content }}</div>
          <div class="c-ops">
            <a @click="startReply(top)">回复</a>
            <a v-if="canDelete(top)" class="danger" @click="remove(top)">删除</a>
          </div>
        </div>
        <ul v-if="top.replies?.length" class="reply-list">
          <li v-for="r in top.replies" :key="r.id">
            <div class="c-row">
              <span class="who">{{ r.user?.nickname ?? '已注销用户' }}</span>
              <span v-if="r.replyTo" class="via">回复 <b>{{ r.replyTo.nickname }}</b></span>
              <span class="when" :title="r.createdAt">{{ relativeTime(r.createdAt) }}</span>
              <div class="c-body">{{ r.content }}</div>
              <div class="c-ops">
                <a @click="startReply(top, r)">回复</a>
                <a v-if="canDelete(r)" class="danger" @click="remove(r)">删除</a>
              </div>
            </div>
          </li>
        </ul>
      </li>
    </ul>
    <p v-if="!loading && canComment && !comments.length" class="empty">还没有评论，来抢沙发～</p>
  </section>
</template>

<style scoped>
.comment-zone { margin-top: 40px; border-top: 1px solid #ebeef5; padding-top: 20px; }
.comment-zone h3 { margin: 0 0 14px; }
.closed-tip, .guest-tip { color: #909399; font-size: 14px; background: #f5f7fa; padding: 12px; border-radius: 6px; }
.guest-tip a { color: #409eff; cursor: pointer; }
.composer { margin-bottom: 18px; }
.reply-bar { font-size: 13px; color: #606266; margin-bottom: 6px; }
.composer-foot { display: flex; justify-content: flex-end; align-items: center; gap: 12px; margin-top: 6px; font-size: 12px; color: #909399; }
.composer-foot .over { color: #f56c6c; }
.comment-list { list-style: none; margin: 0; padding: 0; }
.top-item { padding: 12px 0; border-bottom: 1px dashed #f0f2f5; }
.c-row .who { font-weight: 600; font-size: 14px; margin-right: 8px; }
.c-row .via { font-size: 13px; color: #909399; margin-right: 8px; }
.c-row .when { font-size: 12px; color: #c0c4cc; }
.c-body { margin: 6px 0; line-height: 1.7; word-break: break-word; }
.c-ops { font-size: 12px; }
.c-ops a { color: #909399; cursor: pointer; margin-right: 12px; }
.c-ops a:hover { color: #409eff; }
.c-ops .danger:hover { color: #f56c6c; }
.reply-list { list-style: none; margin: 8px 0 0; padding: 10px 12px; background: #fafbfc; border-radius: 6px; }
.reply-list li { padding: 6px 0; }
.empty { color: #c0c4cc; font-size: 14px; text-align: center; }
</style>
