package com.blog.module.comment.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 评论视图对象（M5）—— 两级嵌套结构，对齐《API接口设计.md》§6 响应示例：
 * 顶级评论带 {@code replies[]}，回复带 {@code replyTo}（被@的人，展示"x 回复 y"）。
 *
 * <p>VO 里为什么内嵌 UserBrief 而不是把昵称/头像拍平：一条评论涉及三个"人"
 * （发表者、被回复者），拍平会产生 userNickname/replyNickname 两套前缀字段，
 * 前端模板里容易张冠李戴；内嵌小对象让 JSON 结构与文档示例逐字段对齐。
 *
 * <p>不暴露 userId 裸值给匿名接口？——暴露的是公开昵称同源的用户 id，
 * 博客场景这本就是公开信息（点击头像进作者主页是 UC-V 用例），且 M3 文章
 * 详情已同样暴露 author.id，口径一致。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CommentVO {

    /** 评论 id */
    private Long id;

    /** 所属文章 id：前端"我的评论"页点击跳文章详情用 */
    private Long articleId;

    /** 文章标题：仅"我的评论"/后台列表场景填充（公开评论区不需要，省一次聚合） */
    private String articleTitle;

    /** 发表者 */
    private UserBrief user;

    /** 被@用户：仅回复场景非空（展示"x 回复 y"） */
    private UserBrief replyTo;

    /** 评论正文（已清洗纯文本） */
    private String content;

    /** 0待审 / 1已通过 / 2已屏蔽：管理端列表用；公开列表恒为 1（查询已过滤） */
    private Integer status;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 子回复（仅顶级评论携带；回复的回复不存在——两级封顶） */
    private List<CommentVO> replies;

    /** 用户摘要（发表者/被@者共用；只带展示三件套，绝不含邮箱等敏感列） */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UserBrief {
        /** sys_user.id */
        private Long id;
        /** 昵称（展示名） */
        private String nickname;
        /** 头像 URL，可空 */
        private String avatar;
    }
}
