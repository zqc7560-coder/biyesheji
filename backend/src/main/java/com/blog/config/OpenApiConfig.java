package com.blog.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Knife4j / SpringDoc 在线接口文档元信息。
 *
 * <p>答辩演示链路：Knife4j 的 doc.html 可直接试调接口，
 * 是《项目计划.md》阶段二验收项"接口文档齐全"的落点。
 * prod 环境通过 application-prod.yml 里 springdoc/knife4j 关闭配置隐藏。
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI blogOpenAPI() {
        return new OpenAPI().info(new Info()
                .title("个人博客系统 API")
                .description("前后端分离个人博客系统（毕业设计）。统一响应体、JWT 认证、AI 摘要/标签模块。"
                        + "契约详见 docs/API接口设计.md")
                .version("v1.0"));
    }
}
