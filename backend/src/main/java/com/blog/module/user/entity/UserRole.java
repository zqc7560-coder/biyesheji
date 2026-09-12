package com.blog.module.user.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户-角色关联实体 —— 对应《数据库设计.md》§3.4 user_role。
 * 多对多连接表（一人可多角色）。注意：本表无 deleted 公共字段——关联关系即删即建，
 * 没有"回收站"语义，逻辑删除反而让"某人当前有哪些角色"的查询变复杂。
 */
@Data
@TableName("user_role")
public class UserRole {

    /** 主键：数据库自增（《数据库设计.md》§1.2：选自增而非雪花，便于课设演示阅读） */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 关联 sys_user.id */
    private Long userId;

    /** 关联 sys_role.id */
    private Long roleId;

    /** 创建时间：DDL 默认 CURRENT_TIMESTAMP */
    private LocalDateTime createdAt;
}
