/**
 * 用户自助接口封装（M10）—— 《API接口设计.md》§3 的最后一块前端拼图。
 *
 * 为什么 M10 才建这个文件：M2 的登录/注册走 auth.js，头像上传当时被
 * UserService 内部通道消化（无独立页面消费）；M10 铺开"账号设置"页才出现
 * 真正的多接口消费者——此时建 user.js 做 URL 契约单点维护，与 api/*.js 家族一致。
 */
import request from './request'

/** GET /api/users/profile?userId=：查看资料（缺省自己；查他人仅公开字段，email 为空） */
export function fetchProfile(userId) {
  return request.get('/users/profile', { params: userId ? { userId } : {} })
}

/** PUT /api/users/profile：{nickname,email,bio}——成功后调用方需刷新 userStore 快照 */
export function updateProfile(data) {
  return request.put('/users/profile', data)
}

/** PUT /api/users/password：{oldPassword,newPassword}——成功即全端下线，调用方必须清态跳登录 */
export function changePassword(data) {
  return request.put('/users/password', data)
}

/** POST /api/users/avatar：multipart file；返回更新后的完整 UserVO（含新 avatar URL） */
export function uploadAvatar(file) {
  const fd = new FormData()
  fd.append('file', file)
  return request.post('/users/avatar', fd, {
    headers: { 'Content-Type': undefined },   // 交还 axios 自动 boundary（api/file.js 同款注释）
    timeout: 60000
  })
}
