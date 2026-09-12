package com.blog.module.ai.controller;

import com.blog.common.BizException;
import com.blog.common.Result;
import com.blog.module.ai.dto.AiGenerateDTO;
import com.blog.module.ai.service.LlmFacade;
import com.blog.module.ai.vo.AiStatusVo;
import com.blog.module.ai.vo.SummaryResult;
import com.blog.security.LoginUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.async.DeferredResult;

import java.util.concurrent.RejectedExecutionException; // 保留引用说明：见下方 catch 注释

/**
 * 用户端 AI 接口（M6，路径对齐《API接口设计.md》§7）。
 *
 * <p>异步模型（《AI模块设计.md》§7）：Controller 把生成任务提交进 ai-* 线程池，
 * 立即返回 DeferredResult(35s)——Tomcat http-nio 线程当场释放，30s 级慢调用
 * 不阻塞文章/评论等主接口。异常传播路径要特别处理：DeferredResult 的 errorResult
 * 不走 @RestControllerAdvice（那是同步调用栈的机制），所以任务体里 catch 后
 * 手工把 BizException 翻译成 Result 信封——降级矩阵的语义才能原样到达前端。
 *
 * <p>超时链（§7.3）：Axios 40s &gt; DeferredResult 35s &gt; 单次 HTTP 30s。
 * 35s 到点仍无结果 → 同样回 4002 降级（用户看到"稍后重试"，而非挂死转圈）。
 */
@Slf4j
@Tag(name = "AI能力", description = "摘要/标签生成与状态探测（课题重点模块，用户端）")
@RestController
@RequestMapping("/api/ai")
public class AiController {

    /** DeferredResult 整体超时：35s（> 单次 HTTP 30s，给重试留一半窗口） */
    private static final long DEFERRED_TIMEOUT_MS = 35_000L;

    private final LlmFacade llmFacade;
    /** AI 专用线程池：显式 @Qualifier 构造注入（字段类型用接口 TaskExecutor，测试可换直连池） */
    private final org.springframework.core.task.TaskExecutor aiExecutor;

    public AiController(LlmFacade llmFacade,
                        @Qualifier("aiExecutor") org.springframework.core.task.TaskExecutor aiExecutor) {
        this.llmFacade = llmFacade;
        this.aiExecutor = aiExecutor;
    }

    /**
     * POST /api/ai/summary —— 生成摘要（登录 + ai:use）。
     *
     * <p>权限口径：文档"登录"列 + M1 种子授权矩阵（普通用户有 ai:use）。
     * 用 hasAuthority 而非 isAuthenticated：与后端其他治理接口同一把尺子，
     * 未来若要收走某角色的 AI 使用权只改 role_permission 数据。
     */
    @Operation(summary = "AI 生成文章摘要")
    @PostMapping("/summary")
    @PreAuthorize("hasAuthority('ai:use')")
    public DeferredResult<ResponseEntity<Result<SummaryResult>>> summary(
            @AuthenticationPrincipal LoginUser me, @Valid @RequestBody AiGenerateDTO dto) {
        Long userId = me.getUserId();
        DeferredResult<ResponseEntity<Result<SummaryResult>>> deferred =
                new DeferredResult<>(DEFERRED_TIMEOUT_MS);
        deferred.onTimeout(() -> deferred.setResult(degrade("AI 生成超时（35s），请稍后重试或手动填写")));
        try {
            aiExecutor.execute(() -> {
                try {
                    SummaryResult result = llmFacade.generateSummary(userId, dto);
                    deferred.setResult(ResponseEntity.ok(Result.ok(result)));
                } catch (BizException e) {
                    // 4001~4005 家族原样进信封（HTTP 状态沿用 BizException.httpStatus，
                    // 4003→429 让前端拦截器统一 toast）；异步路径 advice 不生效，手工翻译
                    deferred.setResult(ResponseEntity.status(e.getHttpStatus())
                            .body(Result.fail(e.getBizCode(), e.getMessage())));
                } catch (Exception e) {
                    log.error("AI 摘要异步任务未预期异常", e);
                    deferred.setResult(degrade("AI 服务暂不可用，请稍后重试或手动填写"));
                }
            });
        } catch (TaskRejectedException e) {
            // ThreadPoolTaskExecutor 把 RejectedExecutionException 包装成 TaskRejectedException
            // 抛出（队列(100)打满）：立即降级，不让用户白等 35s（§3.4 并发维度）
            log.warn("AI 线程池饱和，任务被拒: {}", e.getMessage());
            deferred.setResult(degrade("AI 服务繁忙，请稍后重试"));
        }
        return deferred;
    }

    /**
     * GET /api/ai/status —— 按钮可用态探测（公开；SecurityConfig 白名单放行）。
     *
     * <p>userId 从令牌里"顺带"取（有则算剩余额度，无则 null 跳过）——
     * 同一接口服务游客（只看开关/配置态）与登录用户（看额度），
     * 避免前端为"要不要带 Authorization"分叉两条逻辑。
     */
    @Operation(summary = "AI 功能状态探测（前端按钮置灰依据）")
    @GetMapping("/status")
    public Result<AiStatusVo> status(@AuthenticationPrincipal LoginUser me) {
        return Result.ok(llmFacade.status(me == null ? null : me.getUserId()));
    }

    /** 统一降级信封：4002 + 200 HTTP（AI 失败不是服务端故障，别让 Axios 拦截器当 5xx 弹错误） */
    private ResponseEntity<Result<SummaryResult>> degrade(String message) {
        return ResponseEntity.status(HttpStatus.OK)
                .body(Result.fail(com.blog.common.BizCode.AI_CALL_FAILED, message));
    }
}
