package com.blog.module.system.controller;

import com.blog.common.Result;
import com.blog.module.system.service.SysConfigService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 站点公开参数接口（M8）—— GET /api/system/config（《API接口设计.md》§8"公开"行）。
 *
 * <p>匿名可达：前端顶栏站点名/Logo/备案号在首屏渲染就要用，不能为这几个展示字段
 * 先登录；也因此本接口只出 SysConfigService.PUBLIC_KEYS 白名单子集（泄露面收敛
 * 在服务层，不在这里加过滤——两个入口共用一份白名单才不会漂移）。
 * 路径已登记进 SecurityConfig 的 GET 白名单。
 */
@Tag(name = "系统", description = "站点公开参数")
@RestController
@RequestMapping("/api/system")
@RequiredArgsConstructor
public class SystemPublicController {

    private final SysConfigService sysConfigService;

    /** GET /api/system/config → {site_name, allow_register, comment_audit, ...} */
    @Operation(summary = "站点公开参数（白名单键）")
    @GetMapping("/config")
    public Result<Map<String, String>> config() {
        return Result.ok(sysConfigService.publicConfig());
    }
}
