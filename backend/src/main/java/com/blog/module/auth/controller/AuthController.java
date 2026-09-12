package com.blog.module.auth.controller;

import com.blog.common.Result;
import com.blog.module.auth.dto.LoginDTO;
import com.blog.module.auth.dto.RegisterDTO;
import com.blog.module.auth.service.AuthService;
import com.blog.module.auth.vo.LoginVO;
import com.blog.module.user.vo.UserVO;
import com.blog.security.LoginUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 认证接口（M2）—— 路径/权限逐行对齐《API接口设计.md》§2。
 *
 * <p>@AuthenticationPrincipal LoginUser 是 JwtAuthFilter 写入上下文的当前用户：
 * 身份取自"已验签的令牌"，不接受任何请求参数里的 userId——这就是越权三防的
 * 第一层（JWT 角色）在 Controller 的落点。
 *
 * <p>register/login 在 SecurityConfig 白名单里公开；logout/refresh/me 走
 * anyRequest().authenticated()（持有效令牌即可，无需特定权限点）。
 */
@Tag(name = "认证", description = "注册/登录/登出/续期/当前用户")
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    /**
     * POST /api/auth/register（公开）。
     * 响应 data 按文档为 { "userId": n }——用 Map 承载这个单字段小载荷，
     * 不为其专门建 VO 类（字段数=1，YAGNI）。
     */
    @Operation(summary = "注册")
    @PostMapping("/register")
    public Result<Map<String, Long>> register(@Valid @RequestBody RegisterDTO dto) {
        Long userId = authService.register(dto);
        return Result.ok(Map.of("userId", userId));
    }

    /**
     * POST /api/auth/login（公开）。
     * IP/UA 从请求现场取，写 login_log（成败都记，见 AuthService.login 注释）。
     */
    @Operation(summary = "登录，返回 JWT")
    @PostMapping("/login")
    public Result<LoginVO> login(@Valid @RequestBody LoginDTO dto, HttpServletRequest request) {
        return Result.ok(authService.login(dto, clientIp(request), request.getHeader("User-Agent")));
    }

    /**
     * POST /api/auth/logout（登录）。幂等语义由过滤器保证：能进到这里的请求
     * 必然持有有效且未拉黑的令牌；重复登出第二次会直接 401（jti 已黑）。
     * 前端把 401 当"已登出"处理即可（本地清 token 是必做的）。
     */
    @Operation(summary = "登出（Token 拉黑）")
    @PostMapping("/logout")
    public Result<Void> logout(@AuthenticationPrincipal LoginUser loginUser) {
        authService.logout(loginUser);
        return Result.ok();
    }

    /**
     * POST /api/auth/refresh（登录）。滑动续期：剩余 ≥30min 时 data 回
     * { "renewed": false } 不换发（防无限续命，见 AuthService.refresh）；
     * <30min 换发新令牌 { "renewed": true, token, expiresIn, user }。
     */
    @Operation(summary = "滑动续期（剩余<30min 才换发新 Token）")
    @PostMapping("/refresh")
    public Result<Object> refresh(@AuthenticationPrincipal LoginUser loginUser) {
        LoginVO renewed = authService.refresh(loginUser);
        if (renewed == null) {
            return Result.ok(Map.of("renewed", false));
        }
        // 用 LinkedHashMap 保序输出 renewed=true + 三字段（Knife4j 展示更稳）
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("renewed", true);
        body.put("token", renewed.getToken());
        body.put("expiresIn", renewed.getExpiresIn());
        body.put("user", renewed.getUser());
        return Result.ok(body);
    }

    /** GET /api/auth/me（登录）：当前登录用户信息 + 权限点 */
    @Operation(summary = "当前登录用户信息+权限点")
    @GetMapping("/me")
    public Result<UserVO> me(@AuthenticationPrincipal LoginUser loginUser) {
        return Result.ok(authService.me(loginUser));
    }

    /**
     * 客户端 IP：X-Forwarded-For 首段（Nginx 反代后 remoteAddr 恒为 127.0.0.1，
     * 日志会失去审计价值）；无代理头时回退 remoteAddr。
     * 信任链说明：生产 Nginx 必须用 $remote_addr 覆写 XFF（部署文档 §1.2 配置已含），
     * 否则该头可被客户端伪造——本期课设环境接受此权衡。
     */
    private String clientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            return xff.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
