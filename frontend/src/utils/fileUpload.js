/**
 * 文件上传客户端校验与表单构造（M8 纯函数）。
 *
 * 为什么前端还要校一遍（后端三卡才是真防线）：5MB 文件传到一半被后端拒的
 * 体验是"转圈三秒 → 红色 toast"，前端预检把失败前移到选文件瞬间；且
 * accept 属性让系统文件选择器直接过滤可选类型，减少误操作面。
 * 前端校验只做"扩展名 + 体积"两卡——魔数要读文件字节（FileReader 异步），
 * 为一个体验优化背异步复杂度不值，后端第三卡兜底。
 */

/** 与后端 FileTypeValidator.IMAGE_EXT_WHITELIST 逐值一致（改一处必改两处，注释互指） */
export const IMAGE_EXTENSIONS = ['png', 'jpg', 'jpeg', 'webp', 'gif']

/** 与后端 MAX_SIZE / application.yml max-file-size 同源：5MB */
export const MAX_FILE_BYTES = 5 * 1024 * 1024

/** 与后端 FileTypeValidator.BIZ_TYPES 对齐的业务类型 */
export const UPLOAD_BIZ_TYPES = ['avatar', 'cover', 'article']

/** 取小写扩展名（无扩展名回空串） */
export function fileExtension(name) {
  const s = String(name || '')
  const dot = s.lastIndexOf('.')
  return dot < 0 || dot === s.length - 1 ? '' : s.slice(dot + 1).toLowerCase()
}

/**
 * 上传预检：返回 { ok, message }。
 * message 直接进 toast（与服务端 3001/3002 文案同族语义，用户看到的口径一致）。
 */
export function precheckImage(file) {
  if (!file) return { ok: false, message: '未选择文件' }
  const ext = fileExtension(file.name)
  if (!IMAGE_EXTENSIONS.includes(ext)) {
    return { ok: false, message: `仅支持图片格式: ${IMAGE_EXTENSIONS.join('/')}` }
  }
  if (file.size > MAX_FILE_BYTES) {
    return { ok: false, message: '文件超过 5MB 限制' }
  }
  return { ok: true, message: '' }
}

/**
 * 把上传响应规整成 markdown 插图语法（编辑器"插入图片"动作的产出格式化）。
 * 标题取去扩展名的文件名：alt 文本可读性比一串 uuid 好，且 DOMPurify 渲染链路
 * （M3 管道）对 alt 内容做文本级转义，恶意文件名不会成为注入面。
 */
export function toMarkdownImage(url, originalName) {
  const base = String(originalName || '').replace(/\.[^.]*$/, '') || '图片'
  const safeAlt = base.replace(/[[\]]/g, '')   // 中括号会破坏 ![]() 结构，先摘掉
  return `![${safeAlt}](${url})`
}

/** 上传结果兜底校验：axios 拆信封后仍可能被网关/代理塞回非对象体，取字段前立闸 */
export function assertUploadResult(data) {
  if (!data || typeof data.url !== 'string' || !data.url.startsWith('/uploads/')) {
    throw new Error('上传响应缺少 url 字段')
  }
  return data
}
