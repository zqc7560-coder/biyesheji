/**
 * 评论模块纯函数（M5）—— 页面组件里"值得测"的逻辑全部下沉到这里：
 * 树装配、状态文案、相对时间、@展示文案。与 utils/markdown.js、tagCategory.js
 * 同一策略：纯数据进纯数据出，Vitest node 环境直接断言，不为一条格式化
 * 规则背 jsdom 的安装成本（vite.config.js 环境约束注释）。
 */

/** 审核状态 → 中文标签（CommentVO.status；与后端 CommentService 常量逐值对齐） */
export const COMMENT_STATUS_LABELS = {
  0: '待审核',
  1: '已通过',
  2: '已屏蔽'
}

/** 状态 → el-tag 类型（待审=警示黄 / 通过=绿 / 屏蔽=红），后台列表着色用 */
export const COMMENT_STATUS_TAG = {
  0: 'warning',
  1: 'success',
  2: 'danger'
}

/**
 * 后端返回已是"顶级带 replies"的嵌套结构，但排序职责留在前端：
 * 顶级按 createdAt 正序（对话时间流），回复按 id 正序（服务端已排，这里
 * 稳定化防序列化乱序）。抽出纯函数是因为组件里"评论列表为空要不要显示
 * 发表框"的分支逻辑要跟着长度判定走，可测化统一入口。
 */
export function sortCommentTree(list) {
  const tops = (Array.isArray(list) ? [...list] : []).sort((a, b) =>
    String(a.createdAt ?? '').localeCompare(String(b.createdAt ?? '')) || a.id - b.id)
  for (const top of tops) {
    if (Array.isArray(top.replies)) {
      top.replies = [...top.replies].sort((a, b) => a.id - b.id)
    }
  }
  return tops
}

/**
 * 回复的展示前缀："x 回复 y："（§3.10 reply_to_uid 的设计意图）。
 * 被回复者正是父评论作者时前端仍显示——"@谁"是对话指向，不是冗余信息。
 */
export function replyPrefix(comment) {
  if (!comment || !comment.replyTo) return ''
  return `${comment.replyTo.nickname ?? '未知用户'} 回复 `
}

/**
 * 相对时间（"3 分钟前"）：评论区读的是"新鲜度"不是精确时刻，
 * 精确时间放 title 悬浮兜底（组件里绑定）。阈值取整分钟/整小时/整天，
 * 30 天后退回绝对日期——再"相对"就没有信息量了。
 */
export function relativeTime(iso, now = Date.now()) {
  if (!iso) return ''
  const t = new Date(iso).getTime()
  if (Number.isNaN(t)) return String(iso)
  const diff = Math.max(0, now - t)
  const min = Math.floor(diff / 60000)
  if (min < 1) return '刚刚'
  if (min < 60) return `${min} 分钟前`
  const h = Math.floor(min / 60)
  if (h < 24) return `${h} 小时前`
  const d = Math.floor(h / 24)
  if (d < 30) return `${d} 天前`
  return String(iso).slice(0, 10)
}

/**
 * 评论正文字数钳制的前端镜像（后端 DTO @Size 1000 是硬闸，这里是软提示）：
 * 返回 {ok, over}——输入框下方 "xx/1000" 计数器与"超长禁发"共用这一个判定源，
 * 避免出现两处各自实现 maxLength 而宽度口径漂移。
 */
export const COMMENT_MAX_LENGTH = 1000

export function commentLengthCheck(text) {
  const len = (text ?? '').trim().length
  return { len, ok: len > 0 && len <= COMMENT_MAX_LENGTH, over: len > COMMENT_MAX_LENGTH }
}
