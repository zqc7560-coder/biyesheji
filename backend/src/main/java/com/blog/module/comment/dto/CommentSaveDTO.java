package com.blog.module.comment.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 发表评论请求体（M5）—— 字段逐条对齐《API接口设计.md》§6：
 * {@code {articleId,parentId,replyToUid,content}}。
 *
 * <p>校验口径与 DDL 对齐（《数据库设计.md》§3.10）：content 上限 1000 字符
 * 与 comment.content VARCHAR(1000) 同宽——服务端校验先于截断，超长回 400
 * 而不是静默截尾（用户以为发出去的话被切掉一半，比被拒绝更伤信任）。
 *
 * <p>parentId/replyToUid 不在这里做存在性校验：那需要查库，属于 Service 的
 * 领地（DTO 校验只守"格式/长度"这类无状态规则，两层各管各的才不重复)。
 */
@Data
public class CommentSaveDTO {

    /** 被评论文章 id：必填（评论永远挂在文章下，不存在"全站评论"入口） */
    @NotNull(message = "articleId 不能为空")
    private Long articleId;

    /** 父评论 id：null/0=顶级评论；非 0=对某条顶级评论的回复（两级封顶在 Service 强制） */
    @Min(value = 0, message = "parentId 不能为负数")
    private Long parentId;

    /** 被@的用户 id：仅回复场景由前端带上（"x 回复 y"展示用），Service 校验其真实存在 */
    private Long replyToUid;

    /** 评论正文：纯文本（服务端清洗后入库，见 CommentService.sanitize） */
    @NotBlank(message = "评论内容不能为空")
    @Size(max = 1000, message = "评论内容不能超过 1000 字")
    private String content;
}
