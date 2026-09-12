package com.blog.module.system.controller;

import com.blog.common.Result;
import com.blog.module.system.dto.SystemConfigUpdateDTO;
import com.blog.module.system.service.DashboardService;
import com.blog.module.system.service.SysConfigService;
import com.blog.module.system.vo.DashboardVo;
import com.blog.security.LoginUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 管理端系统配置 + 仪表盘接口（M8）—— 路径与权限点对齐《API接口设计.md》§8：
 * <ul>
 *   <li>GET  /api/admin/system/config    可编辑参数清单（config:manage）</li>
 *   <li>PUT  /api/admin/system/config    批量修改 {items:[{key,value}]}（config:manage）</li>
 *   <li>GET  /api/admin/dashboard        仪表盘聚合（config:manage）</li>
 * </ul>
 *
 * <p>仪表盘为什么挂 config:manage 而不是新造权限点：API 文档没给它专属权限，
 * M1 种子权限表也没有 dashboard:* 键——凭空加权限点要动 RBAC 种子与设计文档，
 * 收益为零（能管全站参数的人看全站统计没有任何越权面）。类级注解统一口径，
 * 三接口不再各自重复声明。
 */
@Tag(name = "管理端-系统", description = "站点参数/仪表盘")
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('config:manage')")
public class AdminSystemController {

    private final SysConfigService sysConfigService;
    private final DashboardService dashboardService;

    /** GET /api/admin/system/config：返回带 type/description 的清单（动态表单渲染依据） */
    @Operation(summary = "可编辑系统参数清单")
    @GetMapping("/system/config")
    public Result<List<Map<String, Object>>> list() {
        return Result.ok(sysConfigService.adminList());
    }

    /**
     * PUT /api/admin/system/config：批量修改。
     * updatedBy 从登录态取（sys_config.updated_by 审计列），不信任请求字段——
     * 与 AdminUserController 的"操作者 id 只来自令牌"同一原则。
     */
    @Operation(summary = "批量修改系统参数")
    @PutMapping("/system/config")
    public Result<List<Map<String, Object>>> update(@AuthenticationPrincipal LoginUser me,
                                                    @Valid @RequestBody SystemConfigUpdateDTO dto) {
        return Result.ok(sysConfigService.update(dto, me.getUserId()));
    }

    /** GET /api/admin/dashboard：全站计数快照 + 近 7 日趋势（UC-A-10 的 P0 子集） */
    @Operation(summary = "仪表盘聚合数据")
    @GetMapping("/dashboard")
    public Result<DashboardVo> dashboard() {
        return Result.ok(dashboardService.snapshot());
    }
}
