package com.blog.config;

import com.blog.common.BizCode;
import com.blog.common.Result;
import com.blog.security.JwtAuthFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import java.nio.charset.StandardCharsets;

/**
 * Spring Security 过滤器链装配（M2）。
 *
 * <p>策略（《系统设计.md》§3.1 / 项目计划阶段三任务 4）：
 * <ul>
 *   <li>无状态：STATELESS Session，认证凭据只有 JWT（不存在 Cookie/Session 通道）；</li>
 *   <li>CSRF 关闭：无 Cookie 自动携带的会话，跨站请求伪造无攻击面（REST + Bearer 标准做法）；</li>
 *   <li>默认拒绝：除公开的 auth/健康检查/文档与只读内容接口外全部要求登录——
 *       "白名单放行"而非"黑名单拦截"，新接口忘记登记时漏的是 401 而不是裸奔；</li>
 *   <li>@PreAuthorize 方法级鉴权启用（《API接口设计.md》§8.3 越权三防的第二层）。</li>
 * </ul>
 *
 * <p>未认证/无权限的响应同样走统一信封：AuthenticationEntryPoint→1001/401，
 * AccessDeniedHandler→1005/403。若用 Spring 默认的 WWW-Authenticate 空 401，
 * 前端 Axios 拦截器将拿不到 code 字段，"1001/1004 跳登录"的契约就会断。
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity   // 开启 @PreAuthorize（Boot3/Spring Security 6 已取代 @EnableGlobalMethodSecurity）
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthFilter jwtAuthFilter;
    private final ObjectMapper objectMapper;

    /** 公开路径白名单：登录注册、健康检查、接口文档、上传直出 */
    private static final String[] PUBLIC_PATHS = {
            "/api/auth/login",
            "/api/auth/register",
            "/api/health",
            "/uploads/**",
            "/doc.html",
            "/webjars/**",
            "/v3/api-docs/**",
            "/favicon.ico",
            "/error"
    };

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .cors(cors -> { /* 放行给 WebMvcConfig 的 addCorsMappings（同源开发代理下几乎不触发） */ })
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(PUBLIC_PATHS).permitAll()
                        // M3：文章只读接口对游客开放（UC-V-01/02 是 P0 游客用例）。
                        // 只放行 GET 且精确到三组路径——写操作与 mine 参数仍要登录；
                        // 草稿/下线的"公开但不可见"不靠这层，由 Service 可见性矩阵兜底
                        // （游客拿到未发布 id 时回 2001，见 ArticleService.assertReadable）。
                        .requestMatchers(org.springframework.http.HttpMethod.GET,
                                "/api/articles", "/api/articles/*", "/api/articles/archive",
                                "/api/users/*/articles").permitAll()
                        // HTTP OPTIONS 预检不带 Authorization 头，必须放行否则跨域调试全挂
                        .requestMatchers(org.springframework.http.HttpMethod.OPTIONS, "/**").permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((req, res, e) ->
                                writeJson(res, HttpStatus.UNAUTHORIZED, BizCode.UNAUTHORIZED))
                        .accessDeniedHandler((req, res, e) ->
                                writeJson(res, HttpStatus.FORBIDDEN, BizCode.FORBIDDEN)))
                // JWT 过滤器插在用户名密码认证器之前：有 Bearer 就在此完成认证，
                // 下游 UsernamePasswordAuthenticationFilter 因已有 Context 而跳过
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    /** 与 JwtAuthFilter.writeError 同款：Security 层异常不经过 MVC，信封手工序列化 */
    private void writeJson(HttpServletResponse response, HttpStatus status, BizCode biz)
            throws java.io.IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(objectMapper.writeValueAsString(Result.fail(biz)));
    }
}
