package com.blog.module.user.vo;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 登录用户信息出参 —— 登录响应 data.user / GET /api/auth/me / PUT profile 共用。
 *
 * <p>为什么单独建 VO 而不直接序列化 SysUser 实体（《API接口设计.md》§8.1 的思路）：
 * SysUser 含 password（BCrypt 散列）与 deleted 等内部列，实体直接出参等于把
 * 密码散列发给客户端——即使"只是散列"也给了离线爆破的原料。VO 是白名单：
 * 只放文档约定的 id/username/nickname/avatar/roles/perms（+me 场景的邮箱/简介/状态），
 * 从类型结构上杜绝敏感列泄露，无需依赖 @JsonIgnore 这类"记得加注解"的软约束。
 */
@Data
@Builder
public class UserVO {

    /** 用户 ID */
    private Long id;

    /** 登录名 */
    private String username;

    /** 昵称（展示名） */
    private String nickname;

    /** 头像 URL */
    private String avatar;

    /** 邮箱：仅本人可见场景填充（me/profile?userId=自己），查他人时置 null —— 防 PII 批量抓取 */
    private String email;

    /** 个人简介：公开展示字段 */
    private String bio;

    /** 账号状态 1正常/0禁用：仅管理端场景填充 */
    private Integer status;

    /** 注册时间：仅管理端用户列表场景填充 */
    private LocalDateTime createdAt;

    /** 角色码列表（ROLE_USER/ROLE_ADMIN）：前端 isAdmin 判定与菜单渲染依据 */
    private List<String> roles;

    /** 权限点列表：前端按钮级显隐依据（后端 @PreAuthorize 才是真防线） */
    private List<String> perms;
}
