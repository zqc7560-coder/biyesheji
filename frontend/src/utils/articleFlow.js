/**
 * 冲突处理策略纯函数（M3）—— 《项目计划.md》D-8 的前端半边：
 * 收到 2004（乐观锁冲突）时，编辑器必须让用户在"保留本地/加载服务器"之间显式选择，
 * 任何自动合并都是数据丢失的温床（两边都可能是"对的"）。
 *
 * 拆成纯函数进 Vitest 的理由与 menu.js 同：决策逻辑不能埋在 .vue 里靠手工点，
 * 它决定用户内容会不会被静默覆盖，值得机械锁死。
 */

/** 后端乐观锁冲突码（BizCode.VERSION_CONFLICT，与后端 common/BizCode.java 对齐） */
export const VERSION_CONFLICT = 2004

/**
 * 保存失败的处置决策。
 * @param {{code:number}} err 拦截器 reject 出的错误（约定挂 .code 业务码）
 * @param {{hasLocalDraft:boolean}} ctx 编辑器现场
 * @returns {{action:'reload'|'ask-conflict'|'toast', message:string}}
 */
export function decideSaveFailure(err, ctx) {
  if (err && err.code === VERSION_CONFLICT) {
    // 冲突：无论有无本地草稿都要"问"，自动 reload 会吞掉未保存的编辑
    return { action: 'ask-conflict', message: '内容已被他人更新：保留本地继续编辑，或加载服务器最新版本？' }
  }
  if (err && err.code === 2001) {
    // 文章已被删除/下线失权：留在编辑页没有意义，引导回列表
    return { action: 'toast', message: err.message || '文章不存在或已删除' }
  }
  // 无业务码 = 网络层错误（axios 的 "Network Error"/"timeout..." 是英文技术文案，
  // 对用户无意义且拦截器已 toast 过一次）：这里回通用中文兜底，不重复透传
  if (!err || err.code == null) {
    return { action: 'toast', message: '保存失败，请稍后重试' }
  }
  return { action: 'toast', message: err.message || '保存失败，请稍后重试' }
}

/**
 * 草稿自动保存的 localStorage 兜底（阶段四任务 5："草稿自动保存 localStorage 兜底"）。
 * key 按文章维度隔离（新建用 'new'），恢复时校验"是否比服务端编辑起点更新"：
 * 用时间戳比较而不是内容 diff——编辑器里用户可能刚敲了字还没保存，
 * 任何"服务器更新则丢弃本地"的策略都必须把决定权交给用户（与 2004 同一原则）。
 */
export function localDraftKey(articleId) {
  return `blog:draft:${articleId ?? 'new'}`
}

/**
 * @returns {{use:'local'|'server'} & {reason:string}} 进入编辑页时的内容起点决策
 */
export function resolveEditorSeed(local, server, serverUpdatedAt) {
  if (!local || !local.contentMd) {
    return { use: 'server', reason: '无本地草稿' }
  }
  if (!server || local.savedAt > new Date(serverUpdatedAt || 0).getTime()) {
    // 本地更新（或服务器内容为空）：默认续写本地——自动保存的目的就是这个场景
    return { use: 'local', reason: '本地草稿更新于服务器版本' }
  }
  return { use: 'server', reason: '服务器版本更新（可能已在他处保存）' }
}
