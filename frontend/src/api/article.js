/**
 * 文章接口封装（M3）—— 路径逐条对齐《API接口设计.md》§4。
 * 集中一处的理由与 auth.js 相同（URL 契约单点维护；request 已拆信封，返回即业务载荷）。
 */
import request from './request'

/** 分页列表：params {pageNum,pageSize,categoryId,tagId,keyword,status,mine,sort,authorId} */
export function listArticles(params) {
  return request.get('/articles', { params })
}

/** 详情（草稿/下线仅作者与 ADMIN 可见，游客拿未发布 id 返回 2001） */
export function fetchArticle(id) {
  return request.get(`/articles/${id}`)
}

/** 新建：status 0 草稿 / 1 发布；返回 {id, wordCount} */
export function createArticle(data) {
  return request.post('/articles', data)
}

/** 更新：必须携带 version（详情回显值），冲突返回 2004 */
export function updateArticle(id, data) {
  return request.put(`/articles/${id}`, data)
}

/** 状态流转：{status} → 后端回中文动作文案（已发布/已下线/已回到草稿） */
export function changeArticleStatus(id, status) {
  return request.patch(`/articles/${id}/status`, { status })
}

/** 逻辑删除 */
export function deleteArticle(id) {
  return request.delete(`/articles/${id}`)
}

/** 归档树 {"2026":{"09":[...]}}（游客可访问） */
export function fetchArchive() {
  return request.get('/articles/archive')
}

/** 某作者的公开文章（用户主页） */
export function fetchUserArticles(userId, params) {
  return request.get(`/users/${userId}/articles`, { params })
}

/** 管理员：强制下线 / 置顶（ADMIN 权限口，USER 令牌 1005） */
export function forceOffline(id) {
  return request.patch(`/articles/${id}/offline`)
}
export function setTop(id, topFlag) {
  return request.patch(`/articles/${id}/top?topFlag=${topFlag}`)
}
