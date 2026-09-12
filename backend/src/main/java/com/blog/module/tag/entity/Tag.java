package com.blog.module.tag.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 标签实体 —— 对应《数据库设计.md》§3.8 tag。
 * name 唯一键 + 30 字上限：AI 生成的候选标签必须经"合法化过滤"（去重/截断/黑名单词）
 * 再入库（《AI模块设计.md》§5.3），防止模型幻觉把标签表灌成垃圾场。
 */
@Data
@TableName("tag")
public class Tag {

    /** 主键：数据库自增（《数据库设计.md》§1.2：选自增而非雪花，便于课设演示阅读） */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 标签名：唯一 */
    private String name;

    /** 标签云展示色（Element Plus 主题色系） */
    private String color;

    /** 冗余使用数：标签云字号权重来源，保存文章的事务内维护 */
    private Integer articleCount;

    /** 创建时间：DDL 默认 CURRENT_TIMESTAMP，insert 时省略即可 */
    private LocalDateTime createdAt;

    /** 更新时间：MySQL 由 ON UPDATE 维护；H2 测试库由应用层负责（差异见 schema-h2.sql 头注释） */
    private LocalDateTime updatedAt;

    /** 逻辑删除标记（全局配置 logic-delete-field=deleted，所有 MP 查询自动拼 deleted=0） */
    @TableLogic
    private Integer deleted;
}
