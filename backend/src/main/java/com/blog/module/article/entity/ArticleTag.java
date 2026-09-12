package com.blog.module.article.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 文章-标签关联实体 —— 对应《数据库设计.md》§3.9 article_tag。
 * 保存文章的同一事务内"先 DELETE 后批量 INSERT"重建全量关联（不做 diff 更新），
 * 逻辑简单且事务边界内不会出现中间态；无 deleted 同 UserRole 的理由。
 */
@Data
@TableName("article_tag")
public class ArticleTag {

    /** 主键：数据库自增（《数据库设计.md》§1.2：选自增而非雪花，便于课设演示阅读） */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 关联 article.id */
    private Long articleId;

    /** 关联 tag.id */
    private Long tagId;

    /** 创建时间：DDL 默认 CURRENT_TIMESTAMP */
    private LocalDateTime createdAt;
}
