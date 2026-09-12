package com.blog.module.article.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 状态流转入参（M3）—— PATCH /api/articles/{id}/status 的 `{ "status": 1 }`
 * （《API接口设计.md》§4：发布/下线/回草稿）。
 *
 * <p>为什么单独一个 DTO 而不复用 ArticleSaveDTO：状态流转是"最小变更"语义——
 * 只动 status/published_at 两列，绝不允许顺手把 title/contentMd 覆盖掉
 * （若用整包 DTO，前端漏传字段会走 MP 的 null-忽略策略，看似没事，
 * 实则把"谁改了什么"的审计语义搅浑）。窄入参即窄权限面。
 */
@Data
public class ArticleStatusDTO {

    /** 目标状态：0 回草稿 / 1 发布 / 2 下线。取值域在此拦，流转矩阵合法性（如 0→2）在 Service 判 */
    @NotNull(message = "status 不能为空")
    @Min(value = 0, message = "status 仅接受 0/1/2")
    @Max(value = 2, message = "status 仅接受 0/1/2")
    private Integer status;
}
