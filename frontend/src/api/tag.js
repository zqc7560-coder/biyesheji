/**
 * 标签接口封装（M4）—— 路径逐条对齐《API接口设计.md》§5。
 */
import request from './request'

/** GET /api/tags —— 列表；cloud=true 返回热度权重（标签云） */
export function fetchTags(cloud = false) {
  return request.get('/tags', { params: cloud ? { cloud: true } : {} })
}

/** POST /api/tags —— 新增 {name,color}（ADMIN） */
export function createTag(data) {
  return request.post('/tags', data)
}

/** PUT /api/tags/{id} —— 修改（ADMIN） */
export function updateTag(id, data) {
  return request.put(`/tags/${id}`, data)
}

/** DELETE /api/tags/{id} —— 删除并解除文章关联（ADMIN） */
export function deleteTag(id) {
  return request.delete(`/tags/${id}`)
}

/** POST /api/tags/merge —— 合并 {fromTagId,toTagId}（ADMIN；from 改挂 to 后逻辑删除） */
export function mergeTags(fromTagId, toTagId) {
  return request.post('/tags/merge', { fromTagId, toTagId })
}
