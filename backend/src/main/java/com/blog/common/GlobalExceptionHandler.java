package com.blog.common;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.HashMap;
import java.util.Map;

/**
 * 全局异常处理器：把一切异常收敛为统一响应体，Controller 层零 try-catch。
 *
 * <p>三条处理策略对应《API接口设计.md》§1.4：
 * <ol>
 *   <li>BizException —— 可预期业务失败：按错误码携带的 httpStatus 返回（401/403/404/429…），
 *       日志用 warn（属正常流程分支，不该刷 error）。</li>
 *   <li>参数校验异常 —— @Valid 失败聚合为字段级 Map 放进 data，HTTP 400，code=400。</li>
 *   <li>兜底 Exception —— 记 error 全栈，但响应体只回"系统内部异常"，
 *       绝不把堆栈泄露给前端（安全测试项）。</li>
 * </ol>
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** 业务异常：文案可信（已本地化），直接透传给前端展示 */
    @ExceptionHandler(BizException.class)
    public ResponseEntity<Result<Void>> handleBiz(BizException e) {
        log.warn("业务异常 uri={} code={} msg={}", e.getMessage(), e.getBizCode().getCode(), e.getMessage());
        return ResponseEntity.status(e.getHttpStatus())
                .body(Result.fail(e.getBizCode(), e.getMessage()));
    }

    /** JSON 请求体校验失败（@RequestBody + @Valid）：聚合成 {字段: 原因} 明细 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Result<Map<String, String>>> handleValid(MethodArgumentNotValidException e) {
        Map<String, String> errors = collectFieldErrors(e.getBindingResult().getFieldErrors(),
                e.getAllErrors().stream().map(Object::toString).findFirst().orElse("参数校验失败"));
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Result.fail(BizCode.PARAM_ERROR, "参数校验失败"));
    }

    /** 表单/查询参数绑定校验失败：同上语义 */
    @ExceptionHandler(BindException.class)
    public ResponseEntity<Result<Void>> handleBind(BindException e) {
        String first = e.getFieldErrors().stream()
                .map(f -> f.getField() + ": " + f.getDefaultMessage())
                .findFirst().orElse("参数校验失败");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Result.fail(BizCode.PARAM_ERROR, first));
    }

    /** 方法不支持：归 400 而非 500，语义更准 */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Result<Void>> handleMethod(HttpRequestMethodNotSupportedException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Result.fail(BizCode.PARAM_ERROR, "请求方法不支持: " + e.getMethod()));
    }

    /**
     * 方法级鉴权失败（@PreAuthorize 拦截，越权三防的第二层）——M2 补充。
     *
     * <p>为什么必须显式声明：AccessDenied 发生在 DispatcherServlet 内部
     * （AOP 代理调 Controller 时），会被下面的兜底 Exception 分支接住变成 500——
     * 越权是正常安全事件，不是系统故障，必须转 403 + 1005
     * （且不泄露"资源存在但你没权限"之外的细节）。
     *
     * <p>版本注记：Spring Security 6.2（Boot 3.2.5 管理版本）里 @PreAuthorize 失败
     * 抛的就是 AccessDeniedException；6.3+ 才引入 AuthorizationDeniedException，
     * 升级 Boot 时在此补上对应类型即可（清单别漏，否则又回归 500）。
     */
    @ExceptionHandler(org.springframework.security.access.AccessDeniedException.class)
    public ResponseEntity<Result<Void>> handleAccessDenied(Exception e) {
        // 未认证用户触发的 AccessDenied 语义是 1001（过滤器没给 Context），
        // 已认证但权限不足才是 1005——按 SecurityContext 是否有 principal 区分
        org.springframework.security.core.Authentication auth =
                org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        boolean anonymous = auth == null || !auth.isAuthenticated()
                || auth instanceof org.springframework.security.authentication.AnonymousAuthenticationToken;
        BizCode code = anonymous ? BizCode.UNAUTHORIZED : BizCode.FORBIDDEN;
        return ResponseEntity.status(anonymous ? HttpStatus.UNAUTHORIZED : HttpStatus.FORBIDDEN)
                .body(Result.fail(code));
    }

    /** 兜底：未知异常记全栈定位问题，对外只回统一文案，防止堆栈/SQL 泄露 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<Void>> handleUnknown(HttpServletRequest req, Exception e) {
        log.error("未捕获异常 uri={}", req.getRequestURI(), e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Result.fail(BizCode.SYSTEM_ERROR));
    }

    /** 把字段错误列表折叠成 Map（预留给需要字段明细的场景，如注册表单逐字段报错） */
    private Map<String, String> collectFieldErrors(java.util.List<FieldError> fieldErrors, String fallback) {
        Map<String, String> map = new HashMap<>();
        for (FieldError fe : fieldErrors) {
            map.putIfAbsent(fe.getField(), fe.getDefaultMessage() == null ? fallback : fe.getDefaultMessage());
        }
        return map;
    }
}
