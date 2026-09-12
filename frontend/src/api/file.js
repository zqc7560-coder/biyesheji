/**
 * 文件上传 API（M8）—— 《API接口设计.md》§8 POST /api/files/upload。
 *
 * multipart 不走 request.js 的 JSON 默认头：axios 检测到 FormData 会自动
 * 生成 boundary 的 Content-Type（手动设 application/json 反而会让后端解析失败，
 * 这是"让库做库擅长的事"的具体一处）。超时单独放大到 60s：5MB 图 + 慢盘
 * 场景下 40s 全局超时（为 AI 调优的）仍可能不够，上传失败重传的代价低但体验差。
 */
import request from './request'
import { assertUploadResult, precheckImage } from '@/utils/fileUpload'

/**
 * 上传图片。
 * @param {File} file 浏览器 File 对象
 * @param {'avatar'|'cover'|'article'} type 业务归类（白名单在 utils/fileUpload）
 * @returns {Promise<{url:string, fileId:number, size:number}>}
 * @throws 预检失败时同步抛 Error(message)——不发请求，省一次往返
 */
export async function uploadImage(file, type = 'article') {
  const check = precheckImage(file)
  if (!check.ok) throw new Error(check.message)
  const fd = new FormData()
  fd.append('file', file)
  fd.append('type', type)
  const data = await request.post('/files/upload', fd, {
    headers: { 'Content-Type': undefined },   // 交还 axios 自动 boundary（见文件头注释）
    timeout: 60000
  })
  return assertUploadResult(data)
}
