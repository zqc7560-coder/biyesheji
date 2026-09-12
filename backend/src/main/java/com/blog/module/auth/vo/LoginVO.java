package com.blog.module.auth.vo;

import com.blog.module.user.vo.UserVO;
import lombok.Builder;
import lombok.Data;

/**
 * 登录/续期成功响应 data（POST /api/auth/login、POST /api/auth/refresh，
 * 载荷结构逐字段对齐《API接口设计.md》§2 示例：token/expiresIn/user）。
 */
@Data
@Builder
public class LoginVO {

    /** 签发的 JWT（HS256，2h TTL）：前端存 localStorage，Axios 拦截器注入 Bearer 头 */
    private String token;

    /** 有效期秒数（7200）：前端可据此本地倒计时提前续期，避免"操作中途突然 401" */
    private long expiresIn;

    /** 登录用户快照（含 roles/perms）：省去登录后再打一次 /me 的往返 */
    private UserVO user;
}
