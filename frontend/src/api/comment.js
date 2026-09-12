/**
 * 评论接口封装（M5）—— 路径逐条对齐《API接口设计.md》§6。
 * request 拦截器已拆信封（返回即业务载荷 data），这里只负责 URL 契约单点维护。
 */
import request from './request'

/** 文章评论区（两级嵌套，公开）：返回 PageResult{list: 顶级[]} */
export function fetchArticleComments(articleId) {
  return request.get('/comments', { params: { articleId } })
}

/** 我的评论（平铺、含全部状态；需登录） */
export function fetchMyComments(params) {
  return request.get('/comments', { params: { mine: 1, ...params } })
}

/**
 * 发表评论/回复：{articleId,parentId,replyToUid,content}。
 * 注意返回的是 {pending, comment}——审核开启时 comment 为 null（等待审核），
 * 调用方按 pending 分支提示，不要盲取 comment。
 */
export function postComment(data) {
  return request.post('/comments', data)
}

/** 删除评论（本人或 ADMIN） */
export function deleteComment(id) {
  return request.delete(`/comments/${id}`)
}

/** 管理端分页（需 comment:audit）：status 0待审/1通过/2屏蔽，不传=全部 */
export function adminListComments(params) {
  return request.get('/admin/comments', { params })
}

/** 审核：status 1 通过 / 2 屏蔽（后端回中文动作文案在拦截器 toast 里消费不到，
 *  成功即提示通用文案——信封 message 是"已通过/已屏蔽"级别的信息，页面自己给） */
export function auditComment(id, status) {
  return request.patch(`/admin/comments/${id}/audit`, { status })
}
