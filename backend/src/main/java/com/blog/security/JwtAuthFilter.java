package com.blog.security;

import com.blog.common.BizCode;
import com.blog.common.Result;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * JWT 认证过滤器（M2）—— 挂在 Spring Security 过滤器链的 UsernamePassword 位置，
 * 时序图蓝本：《系统设计.md》§3.1 "后续受保护请求"段。
 *
 * <p>处理链：取 Authorization: Bearer → 验签（失败 1001 / 过期 1004）
 * → jti 黑名单校验（命中 1001）→ 用户级失效水位校验（签发时间早于水位 ⇒ 1001）
 * → 构造 LoginUser 写入 SecurityContext → 放行。
 *
 * <p>三个关键决策：
 * <ol>
 *   <li><b>无 Token 时不拒绝、静默放行</b>：是否要求登录由下游授权规则决定
 *       （SecurityConfig 的 authenticated()/permitAll()）。这样公开接口
 *       （文章列表）带 Token 也能识别身份（供"仅作者可见草稿"的条件判断），
 *       不把"认证"和"授权"两件事耦合在一个过滤器里。</li>
 *   <li><b>错误直接写统一响应体</b>：过滤器在 DispatcherServlet 之前，
 *       异常不会进 @RestControllerAdvice，所以 401/403 的 Result 信封在这里手动
 *       序列化返回——保证前端 Axios 拦截器拿到的永远是同一信封（1001/1004 跳登录
 *       的契约依赖这点，《API接口设计.md》§1.1）。</li>
 *   <li><b>Redis 故障降级放行</b>：黑名单读写抛异常只记 warn 不拦截——缓存层故障
 *       不应放大成全站不可用（TokenBlacklistService 注释里的权衡，D-6/R-04）。</li>
 * </ol>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtService jwtService;
    private final TokenBlacklistService blacklistService;
    /** 复用容器全局 ObjectMapper：日期格式/驼峰策略与 MVC 层一致，避免两套序列化 */
    private final ObjectMapper objectMapper;

    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        // M6：DeferredResult 的 ASYNC 分派也要重新过本过滤器（默认 true=跳过）。
        // 机制：asyncDispatch 会重放整条 Security 过滤链，URL 级 AuthorizationFilter
        // 要求 /api/ai/** 已认证；若 JwtAuthFilter 被默认跳过，重放时上下文匿名，
        // 已登录用户的异步端点会被误判 1001（MockMvc 实测复现）。无状态 JWT 在
        // ASYNC 阶段照常解析 Bearer 头的代价≈0（不查库不碰 Redis 黑名单以外的路径），
        // 换来的是"异步端点鉴权语义与同步端点完全一致"。
        return false;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            // 无凭证：留给授权层判定 permitAll 还是 401（见类注释决策 1）
            chain.doFilter(request, response);
            return;
        }
        String token = header.substring(BEARER_PREFIX.length()).trim();

        Claims claims;
        try {
            claims = jwtService.parse(token);
        } catch (JwtService.TokenExpiredException e) {
            // 1004 与 1001 分开：前端据此"静默刷新"或"跳登录"（API 文档 §1.4）
            writeError(response, HttpStatus.UNAUTHORIZED, BizCode.TOKEN_EXPIRED);
            return;
        } catch (JwtService.TokenInvalidException e) {
            writeError(response, HttpStatus.UNAUTHORIZED, BizCode.UNAUTHORIZED);
            return;
        }

        try {
            // —— 服务端吊销双闸：精确黑名单 + 用户级水位（详见 TokenBlacklistService）——
            if (blacklistService.isJtiBlacklisted(claims.getId())) {
                writeError(response, HttpStatus.UNAUTHORIZED, BizCode.UNAUTHORIZED);
                return;
            }
            long invalidBeforeMs = blacklistService.getInvalidBefore(
                    claims.get("uid", Number.class).longValue());
            // 签发时刻(毫秒)早于失效水位 ⇒ 该令牌产生于"被踢下线"之前，作废
            // （禁用账号 / 改密 / 角色变更 / 管理员强制下线走这个分支，《API接口设计.md》§8）
            if (invalidBeforeMs > 0 && JwtService.issuedAtMs(claims) < invalidBeforeMs) {
                writeError(response, HttpStatus.UNAUTHORIZED, BizCode.UNAUTHORIZED);
                return;
            }
        } catch (Exception e) {
            // 决策 3：Redis 抖动不放大为全站 401；验签通过的令牌按剩余 TTL 风险自担
            log.warn("令牌吊销缓存不可用，降级放行 uid={}：{}",
                    claims.get("uid"), e.getMessage());
        }

        // 剩余有效期 = exp - now：登出时作为黑名单键 TTL 回传（不必再算一次 exp）
        long remainSec = Math.max(0,
                (claims.getExpiration().getTime() - System.currentTimeMillis()) / 1000);
        LoginUser loginUser = jwtService.toLoginUser(claims, remainSec);

        var authentication = new UsernamePasswordAuthenticationToken(
                loginUser, null, loginUser.getAuthorities());
        authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
        SecurityContextHolder.getContext().setAuthentication(authentication);

        chain.doFilter(request, response);
    }

    /** 过滤器层直接输出统一响应体信封（绕过 MVC，见类注释决策 2） */
    private void writeError(HttpServletResponse response, HttpStatus httpStatus, BizCode biz)
            throws IOException {
        response.setStatus(httpStatus.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(objectMapper.writeValueAsString(Result.fail(biz)));
    }
}
