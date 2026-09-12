package com.blog.module.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 修改个人资料请求体（PUT /api/users/profile，《API接口设计.md》§3"修改昵称/简介/邮箱"）。
 *
 * <p>字段白名单式 DTO：只开 nickname/bio/email 三个口，角色、状态、密码、id
 * 从结构上就无法从请求体注入（防"批量赋值"越权改自己为管理员的经典漏洞）。
 * email 变更时 Service 需重新查重（uk_email），邮箱必填即文档口径。
 */
@Data
public class ProfileUpdateDTO {

    /** 昵称：1~50 字（对齐 sys_user.nickname VARCHAR(50)） */
    @NotBlank(message = "昵称不能为空")
    @Size(max = 50, message = "昵称最长50字")
    private String nickname;

    /** 邮箱：换绑时 Service 查重转 1102 */
    @NotBlank(message = "邮箱不能为空")
    @jakarta.validation.constraints.Email(message = "邮箱格式不正确")
    @Size(max = 100, message = "邮箱最长100字符")
    private String email;

    /** 个人简介：可空（列 VARCHAR(500)） */
    @Size(max = 500, message = "简介最长500字")
    private String bio;
}
