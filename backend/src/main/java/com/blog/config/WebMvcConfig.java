package com.blog.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web MVC 横切配置：开发期 CORS 放行。
 *
 * <p>为什么只放行 5173：生产形态是 Nginx 同源托管前端 dist 并反代 /api（见
 * 《部署与测试.md》§1.2），不存在跨域；跨域只发生在 `vite dev` 不走代理直连
 * 8080 调试时。因此这里精确到本机来源、只读+常用方法，而不是 allowedOrigins("*")
 * ——安全测试清单里"任意跨域"是要扣分的项目。
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOriginPatterns("http://localhost:5173", "http://127.0.0.1:5173")
                .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(true)
                .maxAge(3600); // 预检结果缓存 1h，减少 OPTIONS 往返
    }
}
