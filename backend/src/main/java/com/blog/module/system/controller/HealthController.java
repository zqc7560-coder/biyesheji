package com.blog.module.system.controller;

import com.blog.common.Result;
import com.blog.module.system.service.HealthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 健康检查接口（公开，无需 Token）。
 *
 * <p>路径约定：接口设计文档 Base URL 为 /api，但服务端 context-path 保持 "/"，
 * 前缀由每个 Controller 显式 @RequestMapping("/api/xxx") 承担——
 * 好处是 Nginx 反代规则与代码内路径一一对应，排查 404 时不用在两套心智模型间换算。
 */
@Tag(name = "系统", description = "健康检查与站点参数")
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class HealthController {

    private final HealthService healthService;

    /**
     * GET /api/health —— Docker healthcheck、Nginx 探活、答辩演示"链路贯通"三合一。
     * 返回 200 + 各中间件连通态明细（HTTP 200 恒定，可用性看 data.overall）。
     */
    @Operation(summary = "健康检查：返回 MySQL/Redis 连通态")
    @GetMapping("/health")
    public Result<Map<String, Object>> health() {
        return Result.ok(healthService.check());
    }
}
