package com.blog.module.user.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 权限点实体 —— 对应《数据库设计.md》§3.3 sys_permission。
 * 粒度"资源:动作"（article:save / ai:config...），共 10 个权限点由种子写入；
 * 登录时经 role_permission 聚合后写进 JWT/缓存快照，避免每请求联表查库。
 */
@Data
@TableName("sys_permission")
public class SysPermission {

    /** 主键：数据库自增（《数据库设计.md》§1.2：选自增而非雪花，便于课设演示阅读） */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 权限编码：@PreAuthorize("hasAuthority('ai:config')") 里的字符串就是它 */
    private String permCode;

    /** 中文名：后台权限矩阵展示 */
    private String permName;

    /** 所属模块：auth/article/ai/system...（后台菜单按模块分组渲染） */
    private String module;

    /** 创建时间：DDL 默认 CURRENT_TIMESTAMP，insert 时省略即可 */
    private LocalDateTime createdAt;

    /** 更新时间：MySQL 由 ON UPDATE 维护；H2 测试库由应用层负责（差异见 schema-h2.sql 头注释） */
    private LocalDateTime updatedAt;

    /** 逻辑删除标记（全局配置 logic-delete-field=deleted，所有 MP 查询自动拼 deleted=0） */
    @TableLogic
    private Integer deleted;
}
