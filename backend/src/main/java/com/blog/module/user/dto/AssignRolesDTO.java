package com.blog.module.user.dto;

import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;

/**
 * 分配角色请求体（POST /api/admin/users/{id}/roles，{roleIds:[1,2]}，API 文档 §8）。
 * 整组覆盖语义见 AdminUserService.assignRoles 注释。
 */
@Data
public class AssignRolesDTO {

    /** 角色 ID 集合：必须非空（空集合等价于"剥夺全部角色"，本期不开放这种危险语义） */
    @NotEmpty(message = "roleIds 不能为空")
    private List<Long> roleIds;
}
