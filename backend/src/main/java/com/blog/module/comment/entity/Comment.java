package com.blog.module.comment.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 评论实体 —— 对应《数据库设计.md》§3.10 comment。
 *
 * <p>两级结构约定：parentId=0 为顶级；"回复的回复"仍挂在顶级评论下（replyToUid 记录
 * 被 @ 的人用于展示"x 回复 y"），嵌套深度由服务端强制为 2——层级更深的树在网页
 * 评论区的横向空间里读感很差，这是产品决策不是技术妥协。
 * content 只存纯文本：入库前转义、前端渲染走文本插值，XSS 第一道防线。
 */
@Data
@TableName("comment")
public class Comment {

    /** 主键：数据库自增（《数据库设计.md》§1.2：选自增而非雪花，便于课设演示阅读） */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 被评论文章 article.id */
    private Long articleId;

    /** 发表者 sys_user.id */
    private Long userId;

    /** 父评论 ID：0=顶级 */
    private Long parentId;

    /** 被@的用户：仅回复时有值，展示用 */
    private Long replyToUid;

    /** 评论正文（纯文本，≤1000 字） */
    private String content;

    /** 0待审核 / 1已通过 / 2已屏蔽：受 sys_config.comment_audit 开关影响（M5 状态机） */
    private Integer status;

    /** 创建时间：DDL 默认 CURRENT_TIMESTAMP，insert 时省略即可 */
    private LocalDateTime createdAt;

    /** 更新时间：MySQL 由 ON UPDATE 维护；H2 测试库由应用层负责（差异见 schema-h2.sql 头注释） */
    private LocalDateTime updatedAt;

    /** 逻辑删除标记（全局配置 logic-delete-field=deleted，所有 MP 查询自动拼 deleted=0） */
    @TableLogic
    private Integer deleted;
}
