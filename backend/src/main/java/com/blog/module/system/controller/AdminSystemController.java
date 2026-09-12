package com.blog.module.system.controller;

import com.blog.common.Result;
import com.blog.job.PvSyncJob;
import com.blog.module.article.service.ArticleCacheService;
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
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
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
    /** M9：缓存运维口（叶子组件注入，Controller→Service 分层约定下它是唯一"直通"，
     *  因为清缓存本身就是纯 Redis 运维动作，再造一层业务 Service 是空转） */
    private final ArticleCacheService articleCacheService;
    /** M9：手动回写触发口注入（Job 是纯编排组件不持事务状态，Controller 直调不破坏分层） */
    private final PvSyncJob pvSyncJob;

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

    // ==================== M9 缓存运维口（R-04 应急预案的前端落地） ====================

    /**
     * GET /api/admin/cache/stats：缓存体系健康度（各键族数量/补偿队列深度）。
     * 挂在 config:manage 下与仪表盘同一口径：能看全站参数的人看缓存指标零越权面。
     */
    @Operation(summary = "缓存健康度统计")
    @GetMapping("/cache/stats")
    public Result<Map<String, Long>> cacheStats() {
        return Result.ok(articleCacheService.cacheStats());
    }

    /**
     * DELETE /api/admin/cache：一键清空 blog:* 展示缓存（R-04 应急口）。
     *
     * <p>边界在 ArticleCacheService#clearBlogCache 已论证：只清详情+列表键，
     * PV 计数器（未落账的浏览量）、AI 结果缓存（清一次=多花一次钱）、
     * Token 黑名单（安全键）都不在射程。返回删除数供操作者确认动作真的发生了。
     */
    @Operation(summary = "一键清空展示缓存")
    @DeleteMapping("/cache")
    public Result<Map<String, Object>> clearCache() {
        long deleted = articleCacheService.clearBlogCache();
        return Result.ok(Map.of("deleted", deleted,
                "note", "仅清文章详情/列表展示缓存；PV 计数、AI 结果缓存、Token 黑名单不受影响"));
    }

    /**
     * POST /api/admin/cache/pv-sync：手动触发一轮 PV 回写 + 补偿排空。
     *
     * <p>为什么要有这个口：回写任务 5min 节拍（fixedDelay），而"计数落库"是运维
     * 现场最常要确认的一环——演示/答辩/冒烟都要"立刻看到账"，让人守 5 分钟是
     * 反人类的。安全面：syncNow 幂等（取走即归零），重复触发不会双计；
     * fixedDelay 语义保证调度轮与本口并发时最坏是"各取走一部分键"，
     * 单笔增量仍只被取走一次，账不会乱。挂 config:manage 与仪表盘同口径。
     */
    @Operation(summary = "手动触发一轮PV回写")
    @PostMapping("/cache/pv-sync")
    public Result<Map<String, Object>> triggerPvSync() {
        PvSyncJob.Result r = pvSyncJob.syncNow();
        return Result.ok(Map.of(
                "applied", r.applied(),
                "totalDelta", r.totalDelta(),
                "recovered", r.recovered()));
    }
}
