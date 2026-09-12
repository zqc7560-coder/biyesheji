package com.blog.module.user.controller;

import com.blog.common.PageResult;
import com.blog.common.Result;
import com.blog.module.user.dto.AssignRolesDTO;
import com.blog.module.user.service.AdminUserService;
import com.blog.module.user.vo.UserVO;
import com.blog.security.LoginUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 管理端用户治理接口（M2）—— 路径对齐《API接口设计.md》§8。
 *
 * <p>权限双层闸：SecurityConfig 已要求 authenticated()，这里再挂
 * @PreAuthorize("hasAuthority('user:manage')")——权限点而非 ROLE_ADMIN 硬编码，
 * 好处是日后给"内容编辑"等中间角色单独放开 user:manage 时零改动
 * （越权三防第二层；M1 种子里只有 ROLE_ADMIN 持有 user:manage，语义等价现在）。
 */
@Tag(name = "管理端-用户", description = "用户分页/启禁/角色分配")
@RestController
@RequestMapping("/api/admin/users")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('user:manage')")
public class AdminUserController {

    private final AdminUserService adminUserService;

    /** GET /api/admin/users?keyword&status&pageNum&pageSize（ADMIN 用户分页） */
    @Operation(summary = "用户分页查询")
    @GetMapping
    public Result<PageResult<UserVO>> page(@RequestParam(required = false) String keyword,
                                           @RequestParam(required = false) Integer status,
                                           @RequestParam(defaultValue = "1") long pageNum,
                                           @RequestParam(defaultValue = "10") long pageSize) {
        return Result.ok(adminUserService.pageUsers(keyword, status, pageNum, pageSize));
    }

    /**
     * PATCH /api/admin/users/{id}/status（禁用即拉黑其登录态）。
     * 操作者 id 从登录态取（审计日志用 + "禁自己"护栏），不信任任何请求字段。
     */
    @Operation(summary = "启用/禁用账号")
    @PatchMapping("/{id}/status")
    public Result<Void> changeStatus(@AuthenticationPrincipal LoginUser me,
                                     @PathVariable Long id,
                                     @RequestBody Map<String, Integer> body) {
        adminUserService.changeStatus(me.getUserId(), id, body.get("status"));
        return Result.ok();
    }

    /** POST /api/admin/users/{id}/roles（整组覆盖式角色分配；变更后目标用户全端下线） */
    @Operation(summary = "分配角色")
    @PostMapping("/{id}/roles")
    public Result<Void> assignRoles(@AuthenticationPrincipal LoginUser me,
                                    @PathVariable Long id,
                                    @Valid @RequestBody AssignRolesDTO dto) {
        adminUserService.assignRoles(me.getUserId(), id, dto.getRoleIds());
        return Result.ok();
    }
}
