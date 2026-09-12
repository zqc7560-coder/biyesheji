package com.blog.module.system.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 系统参数实体 —— 对应《数据库设计.md》§3.14 sys_config。
 * key-value 型站点参数（site_name/allow_register/ai_enabled...）。
 * 读取模式：启动全量加载进 Redis blog 命名空间 sys:config 哈希镜像，读走缓存；
 * 修改走双删（DB→DEL→短暂延迟再 DEL），config_type 告诉前端渲染什么控件。
 */
@Data
@TableName("sys_config")
public class SysConfig {

    /** 主键：数据库自增（《数据库设计.md》§1.2：选自增而非雪花，便于课设演示阅读） */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 参数键：uk_key 唯一（键名清单见设计文档 §3.14，前后端按字面量对齐） */
    private String configKey;

    /** 参数值：统一字符串存（标量或 JSON），按 configType 解释 */
    private String configValue;

    /** 值类型：string/number/boolean/json（前端动态表单渲染依据） */
    private String configType;

    /** 中文说明：后台配置页 tooltip */
    private String description;

    /** 最后修改管理员：审计追责 */
    private Long updatedBy;

    /** 创建时间：DDL 默认 CURRENT_TIMESTAMP，insert 时省略即可 */
    private LocalDateTime createdAt;

    /** 更新时间：MySQL 由 ON UPDATE 维护；H2 测试库由应用层负责（差异见 schema-h2.sql 头注释） */
    private LocalDateTime updatedAt;

    /** 逻辑删除标记（全局配置 logic-delete-field=deleted，所有 MP 查询自动拼 deleted=0） */
    @TableLogic
    private Integer deleted;
}
