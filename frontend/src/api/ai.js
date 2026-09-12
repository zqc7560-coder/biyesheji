/**
 * AI 模块接口封装（M6）—— 路径逐条对齐《API接口设计.md》§7。
 * 用户端：summary/status；管理端：configs CRUD/默认/测试/日志。
 */
import request from './request'

/** POST /api/ai/summary —— AI 生成摘要（登录+ai:use；异步端点，超时见 request 40s） */
export function aiSummary(data) {
  return request.post('/ai/summary', data)
}

/** GET /api/ai/status —— 按钮可用态探测（公开；登录态带 Authorization 则含剩余额度） */
export function aiStatus() {
  return request.get('/ai/status')
}

/** GET /api/admin/ai/configs —— 配置分页（仅掩码，ADMIN） */
export function fetchAiConfigs(pageNum = 1, pageSize = 10) {
  return request.get('/admin/ai/configs', { params: { pageNum, pageSize } })
}

/** POST /api/admin/ai/configs —— 新增（Key 仅写入不回显） */
export function createAiConfig(data) {
  return request.post('/admin/ai/configs', data)
}

/** PUT /api/admin/ai/configs/{id} —— 修改（apiKey 空串=保持原 Key 不变） */
export function updateAiConfig(id, data) {
  return request.put(`/admin/ai/configs/${id}`, data)
}

/** DELETE /api/admin/ai/configs/{id} —— 删除（内置行后端回 4006 拒绝） */
export function deleteAiConfig(id) {
  return request.delete(`/admin/ai/configs/${id}`)
}

/** PATCH /api/admin/ai/configs/{id}/default —— 设为默认（事务互斥） */
export function setAiDefault(id) {
  return request.patch(`/admin/ai/configs/${id}/default`)
}

/** POST /api/admin/ai/configs/{id}/test —— 连通测试（真实外呼，按钮要 loading） */
export function testAiConfig(id) {
  return request.post(`/admin/ai/configs/${id}/test`)
}

/** GET /api/admin/ai/logs —— 调用日志分页（ADMIN, ai:log） */
export function fetchAiLogs(params) {
  return request.get('/admin/ai/logs', { params })
}
