package com.blog.module.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 注册请求体（POST /api/auth/register，《API接口设计.md》§2）。
 *
 * <p>校验规则逐条对齐 API 文档："username 4~20 位字母数字下划线；password ≥8 位含
 * 字母数字；email 格式"。把规则声明在 DTO 而不是散在 Service 手写 if，是为了：
 * 1) @Valid 失败由全局异常处理器统一转 400 字段明细（与文档错误语义一致）；
 * 2) Knife4j 文档能自动渲染约束，前后端契约一页可见。
 */
@Data
public class RegisterDTO {

    /** 登录名：正则即 API 文档的"字母数字下划线 4~20 位"；首尾空白在 Service trim */
    @NotBlank(message = "用户名不能为空")
    @Pattern(regexp = "^\\w{4,20}$", message = "用户名须为4~20位字母数字下划线")
    private String username;

    /**
     * 明文密码：仅存在于本次请求内存，Service 校验强度后立刻 BCrypt 散列入库，
     * 绝不出现在任何 VO/日志（toString 排除见下）。
     * ≥8 位且必须同时含字母与数字（前瞻断言实现"含字母含数字"）。
     */
    @NotBlank(message = "密码不能为空")
    @Pattern(regexp = "^(?=.*[A-Za-z])(?=.*\\d).{8,}$", message = "密码须≥8位且包含字母和数字")
    private String password;

    /** 昵称：允许重复（区别于 username 唯一性），1~50 字对齐列长 VARCHAR(50) */
    @NotBlank(message = "昵称不能为空")
    @Size(max = 50, message = "昵称最长50字")
    private String nickname;

    /** 邮箱：唯一（uk_email），注册后不可重复注册 */
    @NotBlank(message = "邮箱不能为空")
    @Email(message = "邮箱格式不正确")
    @Size(max = 100, message = "邮箱最长100字符")
    private String email;
}
