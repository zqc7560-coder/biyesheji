package com.blog.module.comment.controller;

import com.blog.common.BizCode;
import com.blog.common.BizException;
import com.blog.common.PageResult;
import com.blog.common.Result;
import com.blog.module.comment.dto.CommentAuditDTO;
import com.blog.module.comment.dto.CommentSaveDTO;
import com.blog.module.comment.service.CommentService;
import com.blog.module.comment.vo.CommentVO;
import com.blog.security.LoginUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 评论接口（M5）—— 路径逐条对齐《API接口设计.md》§6 五接口。
 *
 * <p>分层职责与 M3/M4 一致：本层只做参数归拢、权限粗筛（@PreAuthorize）、
 * 信封包装；两级结构校验、审核开关、计数结算全在 CommentService。
 *
 * <p>GET /api/comments 的鉴权口径（文档"公开"列 mine 语义的延伸）：
 * SecurityConfig 只放行该路径的 GET，但 {@code mine=1} 分支要求登录——
 * 匿名传 mine=1 在方法内显式抛 1001（与 M3 的 mine 口径同款：白名单放行的是
 * "评论区"，不是"任意用户的评论历史"）。
 */
@Tag(name = "评论", description = "发表/两级查询/删除/审核")
@RestController
@RequiredArgsConstructor
public class CommentController {

    private final CommentService commentService;

    /**
     * GET /api/comments —— 双模式读：
     * 默认按 articleId 返回该文章的已通过评论（两级嵌套）；
     * mine=1 返回"我的评论"平铺列表（UC-U-12，含待审/被屏蔽状态对自己透明）。
     */
    @Operation(summary = "文章评论列表（两级）/ 我的评论")
    @GetMapping("/api/comments")
    public Result<PageResult<CommentVO>> list(@AuthenticationPrincipal LoginUser me,
                                              @RequestParam(required = false) Long articleId,
                                              @RequestParam(required = false, defaultValue = "0") int mine,
                                              @RequestParam(defaultValue = "1") long pageNum,
                                              @RequestParam(defaultValue = "20") long pageSize) {
        if (mine == 1) {
            if (me == null) {
                throw new BizException(BizCode.UNAUTHORIZED, BizCode.UNAUTHORIZED.getMessage(), 401);
            }
            return Result.ok(commentService.myComments(me,
                    Math.max(1, pageNum), Math.min(Math.max(1, pageSize), 100)));
        }
        if (articleId == null) {
            return Result.fail(BizCode.PARAM_ERROR, "articleId 不能为空");
        }
        return Result.ok(commentService.articleComments(articleId, me));
    }

    /** POST /api/comments —— 发表评论/回复（登录；禁言拦截与两级校验在 Service） */
    @Operation(summary = "发表评论")
    @PostMapping("/api/comments")
    public Result<Map<String, Object>> create(@AuthenticationPrincipal LoginUser me,
                                              @Valid @RequestBody CommentSaveDTO dto) {
        Map<String, Object> data = commentService.publish(me, dto);
        // 文案分支对齐文档 §6：先审后发→"等待审核"；直发→"发表成功"
        boolean pending = Boolean.TRUE.equals(data.get("pending"));
        return new Result<>(0, pending ? "评论已提交，等待审核" : "评论发表成功",
                data, System.currentTimeMillis());
    }

    /** DELETE /api/comments/{id} —— 删除（本人或 ADMIN；文档权限列"作者"） */
    @Operation(summary = "删除评论（本人或管理员）")
    @DeleteMapping("/api/comments/{id}")
    public Result<Void> delete(@AuthenticationPrincipal LoginUser me,
                               @PathVariable Long id) {
        commentService.delete(me, id);
        return Result.ok();
    }

    /** GET /api/admin/comments —— 管理端分页（UC-A-05 审核队列，?status=0 即待审列表） */
    @Operation(summary = "评论分页（管理端）")
    @GetMapping("/api/admin/comments")
    @PreAuthorize("hasAuthority('comment:audit')")
    public Result<PageResult<CommentVO>> adminList(@RequestParam(required = false) Integer status,
                                                   @RequestParam(defaultValue = "1") long pageNum,
                                                   @RequestParam(defaultValue = "20") long pageSize) {
        return Result.ok(commentService.adminPage(status,
                Math.max(1, pageNum), Math.min(Math.max(1, pageSize), 100)));
    }

    /** PATCH /api/admin/comments/{id}/audit —— 审核：通过(1)/屏蔽(2) */
    @Operation(summary = "评论审核（管理员）")
    @PatchMapping("/api/admin/comments/{id}/audit")
    @PreAuthorize("hasAuthority('comment:audit')")
    public Result<Void> audit(@PathVariable Long id, @Valid @RequestBody CommentAuditDTO dto) {
        String message = commentService.audit(id, dto.getStatus());
        return new Result<>(0, message, null, System.currentTimeMillis());
    }
}
