/**
 * 系统配置与仪表盘 API（M8）—— 《API接口设计.md》§8 系统配置与存储模块。
 * 权限口径由后端裁决（config:manage），前端只做调用封装；
 * 公开配置 fetchPublicConfig 匿名可调（首屏顶栏站点名），失败由调用方静默降级。
 */
import request from './request'

/** GET /api/system/config → { site_name, site_logo, site_icp, allow_register, comment_audit } */
export function fetchPublicConfig() {
  return request.get('/system/config')
}

/** GET /api/admin/system/config → [{ key, value, type, description }]（动态表单渲染依据） */
export function fetchAdminConfigList() {
  return request.get('/admin/system/config')
}

/**
 * PUT /api/admin/system/config：批量提交 { items: [{key,value}] }。
 * 只把"表单里被改动过的键"发出去（调用方负责 diff）——整表提交会让
 * 并发管理员的修改被后提交者全量覆盖，最小变更集把竞态窗口缩到改动键本身。
 */
export function updateSystemConfig(items) {
  return request.put('/admin/system/config', { items })
}

/** GET /api/admin/dashboard → DashboardVo（counts/aiTrend/pvTrend/totalPv/note） */
export function fetchDashboard() {
  return request.get('/admin/dashboard')
}
