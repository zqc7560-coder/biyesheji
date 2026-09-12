package com.blog.module.category.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 分类实体 —— 对应《数据库设计.md》§3.7 category。
 * 两级结构约定：parentId=0 为顶级，本期不再深（前端导航两列够用）；
 * articleCount 是冗余计数（分类页免 COUNT(*)），保存文章的事务内 +/- 维护（M4）。
 */
@Data
@TableName("category")
public class Category {

    /** 主键：数据库自增（《数据库设计.md》§1.2：选自增而非雪花，便于课设演示阅读） */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 分类名：uk_name 唯一（重名直接 1103） */
    private String name;

    /** URL 别名（可选，默认用 ID） */
    private String slug;

    /** 父分类 ID：0=顶级 */
    private Long parentId;

    /** 导航排序：越小越靠前 */
    private Integer sortOrder;

    /** 分类描述 */
    private String description;

    /** 冗余文章数 */
    private Integer articleCount;

    /** 创建时间：DDL 默认 CURRENT_TIMESTAMP，insert 时省略即可 */
    private LocalDateTime createdAt;

    /** 更新时间：MySQL 由 ON UPDATE 维护；H2 测试库由应用层负责（差异见 schema-h2.sql 头注释） */
    private LocalDateTime updatedAt;

    /** 逻辑删除标记（全局配置 logic-delete-field=deleted，所有 MP 查询自动拼 deleted=0） */
    @TableLogic
    private Integer deleted;
}
