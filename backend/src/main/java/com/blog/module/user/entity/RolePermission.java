package com.blog.module.user.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 角色-权限关联实体 —— 对应《数据库设计.md》§3.5 role_permission。
 * 鉴权链路：sys_role → 本表 → sys_permission 聚合出权限点集合。
 * 与 UserRole 同理不设 deleted：权限矩阵调整是"重算覆盖"而非"回收"。
 */
@Data
@TableName("role_permission")
public class RolePermission {

    /** 主键：数据库自增（《数据库设计.md》§1.2：选自增而非雪花，便于课设演示阅读） */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 关联 sys_role.id */
    private Long roleId;

    /** 关联 sys_permission.id */
    private Long permissionId;

    /** 创建时间：DDL 默认 CURRENT_TIMESTAMP */
    private LocalDateTime createdAt;
}
