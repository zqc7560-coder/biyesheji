/**
 * 资料/安全设置的客户端校验（M10 纯函数）—— UC-U-02/03 表单前置闸。
 *
 * 规则与后端 DTO 逐条对齐（ProfileUpdateDTO / ChangePasswordDTO 的注解是单一事实源，
 * 这里的每个魔数都注明出处）：昵称 1~50、邮箱格式+≤100、简介 ≤500、
 * 新密码 ^(?=.*[A-Za-z])(?=.*\d).{8,}$。前端校验的目的不是安全（服务端必再校验），
 * 而是把"提交后才红字"的往返延迟前移到输入瞬间——表单体验的基本礼貌。
 */

/** 后端 ProfileUpdateDTO：@NotBlank nickname @Size(max=50)；email @Email @Size(max=100)；bio @Size(max=500) */
export function validateProfile(form) {
  const errs = {}
  const nickname = String(form?.nickname ?? '').trim()
  if (!nickname) errs.nickname = '昵称不能为空'
  else if (nickname.length > 50) errs.nickname = '昵称最长50字'

  const email = String(form?.email ?? '').trim()
  // 宽松式邮箱正则：与后端 jakarta @Email 的口径求交集而非复刻（jakarta 的实现细节
  // 不是公开契约）——前端卡"明显非法"，真正的唯一性/格式裁决在服务端（1102 兜底）
  if (!email) errs.email = '邮箱不能为空'
  else if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email)) errs.email = '邮箱格式不正确'
  else if (email.length > 100) errs.email = '邮箱最长100字符'

  if (String(form?.bio ?? '').length > 500) errs.bio = '简介最长500字'
  return errs
}

/** 后端 ChangePasswordDTO：@Pattern ^(?=.*[A-Za-z])(?=.*\d).{8,}$（≥8 位含字母数字） */
export function validatePasswordChange(form) {
  const errs = {}
  if (!form?.oldPassword) errs.oldPassword = '请输入旧密码'
  const np = String(form?.newPassword ?? '')
  // 逐字对齐后端 Pattern：先查长度再查字符类，错误文案按"缺什么"分岔（一次提示全）
  if (!np) errs.newPassword = '请输入新密码'
  else if (!/^(?=.*[A-Za-z])(?=.*\d).{8,}$/.test(np)) errs.newPassword = '须≥8位且包含字母和数字'
  else if (np === form.oldPassword) errs.newPassword = '新密码不能与旧密码相同'
  if (form?.confirmPassword !== undefined && form.confirmPassword !== np)
    errs.confirmPassword = '两次输入的新密码不一致'
  return errs
}

/**
 * 头像尺寸预检：复用 fileUpload.precheckImage 之外补一条"必须是图片扩展名"的
 * 提示文案差异化（头像场景用户最可能误传截图目录里的 .bmp——扩展名白名单同样
 * 与后端 FileTypeValidator 同源，见 fileUpload.IMAGE_EXTENSIONS 注释互指）。
 */
export function avatarPickHint() {
  return '支持 png/jpg/jpeg/webp/gif，≤5MB'
}
