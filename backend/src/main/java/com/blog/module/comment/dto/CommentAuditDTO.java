package com.blog.module.comment.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 评论审核请求体（M5）—— PATCH /api/admin/comments/{id}/audit，
 * 对齐《API接口设计.md》§6：{@code {"status":1}} 通过 / {@code 2} 屏蔽。
 *
 * <p>为什么用 @Min(1)@Max(2) 而不接受回 0（打回待审）：审核是"终局裁决"语义——
 * 屏蔽后如需放行，重新 audit 为 1 即可；"退回待审"会让同一条评论在队列里
 * 反复横跳，审计轨迹（谁在何时判了什么）反而变糊。收窄入口永远比放开再收便宜。
 */
@Data
public class CommentAuditDTO {

    /** 目标状态：1 已通过 / 2 已屏蔽 */
    @NotNull(message = "status 不能为空")
    @Min(value = 1, message = "审核仅支持 通过(1)/屏蔽(2)")
    @Max(value = 2, message = "审核仅支持 通过(1)/屏蔽(2)")
    private Integer status;
}
