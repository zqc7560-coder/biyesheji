package com.blog.module.user.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 角色实体 —— 对应《数据库设计.md》§3.2 sys_role。
 * 内置两行 ROLE_USER(id=1)/ROLE_ADMIN(id=2)，由种子脚本写入（显式 id，授权 SQL 依赖编号）；
 * 本期不做角色管理界面，角色集合视为常量。
 */
@Data
@TableName("sys_role")
public class SysRole {

    /** 主键：数据库自增（《数据库设计.md》§1.2：选自增而非雪花，便于课设演示阅读） */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 角色码：ROLE_USER / ROLE_ADMIN —— JWT 声明与 @PreAuthorize 判定用它而非中文名 */
    private String roleCode;

    /** 显示名：仅后台列表展示 */
    private String roleName;

    /** 角色说明 */
    private String description;

    /** 创建时间：DDL 默认 CURRENT_TIMESTAMP，insert 时省略即可 */
    private LocalDateTime createdAt;

    /** 更新时间：MySQL 由 ON UPDATE 维护；H2 测试库由应用层负责（差异见 schema-h2.sql 头注释） */
    private LocalDateTime updatedAt;

    /** 逻辑删除标记（全局配置 logic-delete-field=deleted，所有 MP 查询自动拼 deleted=0） */
    @TableLogic
    private Integer deleted;
}
