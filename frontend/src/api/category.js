/**
 * 分类接口封装（M4）—— 路径逐条对齐《API接口设计.md》§5。
 * request 已拆信封，返回即业务载荷（分类树数组）。
 */
import request from './request'

/** GET /api/categories —— 分类树 [{id,name,slug,parentId,sortOrder,articleCount,children}]（公开） */
export function fetchCategoryTree() {
  return request.get('/categories')
}

/** POST /api/categories —— 新增 {name,slug?,parentId?,sortOrder?,description?}（ADMIN） */
export function createCategory(data) {
  return request.post('/categories', data)
}

/** PUT /api/categories/{id} —— 修改（null/缺省字段=不变更）（ADMIN） */
export function updateCategory(id, data) {
  return request.put(`/categories/${id}`, data)
}

/** DELETE /api/categories/{id} —— 删除（有文章 2101 / 有子分类 2102）（ADMIN） */
export function deleteCategory(id) {
  return request.delete(`/categories/${id}`)
}
