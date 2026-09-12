package com.blog.module.ai.controller;

import com.blog.common.PageResult;
import com.blog.common.Result;
import com.blog.module.ai.dto.AiConfigSaveDTO;
import com.blog.module.ai.service.AiCallLogService;
import com.blog.module.ai.service.AiConfigService;
import com.blog.module.ai.service.LlmFacade;
import com.blog.module.ai.vo.AiConfigVo;
import com.blog.module.ai.vo.AiLogVo;
import com.blog.module.ai.vo.TestResult;
import com.blog.security.LoginUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * 管理端 AI 接口（M6，路径对齐《API接口设计.md》§7 的 /api/admin/ai/** 家族）。
 *
 * <p>权限分层沿用全项目口径（越权三防）：全部挂 ai:config 权限点
 * （日志查询按文档语义给 ai:log——只读与写配置分离，方便未来给"审计员"角色只放日志）。
 * 内置模型保护、唯一默认、Key 加密等业务规则在 AiConfigService/AiFacade，
 * 本层依旧零业务（M3~M5 一致的分层纪律）。
 *
 * <p>testConnection 是唯一"会真花钱"的管理接口：权限收紧到 ai:config，
 * 且调用者 id 进日志（谁在试 Key 可审计）。
 */
@Tag(name = "AI管理", description = "模型配置 CRUD/设默认/连通测试/调用日志（ADMIN）")
@RestController
@RequestMapping("/api/admin/ai")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('ai:config')")
public class AiAdminController {

    private final AiConfigService configService;
    private final LlmFacade llmFacade;
    private final AiCallLogService callLogService;

    /** GET /api/admin/ai/configs —— 配置分页（仅掩码，无 Key 字段的 VO） */
    @Operation(summary = "AI 配置分页列表")
    @GetMapping("/configs")
    public Result<PageResult<AiConfigVo>> page(@RequestParam(defaultValue = "1") long pageNum,
                                               @RequestParam(defaultValue = "10") long pageSize) {
        return Result.ok(configService.page(pageNum, pageSize));
    }

    /** POST /api/admin/ai/configs —— 新增（Key 仅写入不回显；空 Key=先建行后补 Key） */
    @Operation(summary = "新增 AI 配置")
    @PostMapping("/configs")
    public Result<AiConfigVo> create(@Valid @RequestBody AiConfigSaveDTO dto) {
        return Result.ok(configService.create(dto));
    }

    /** PUT /api/admin/ai/configs/{id} —— 修改（apiKey 空=不变更；内置行禁改身份字段） */
    @Operation(summary = "修改 AI 配置")
    @PutMapping("/configs/{id}")
    public Result<AiConfigVo> update(@PathVariable Long id, @Valid @RequestBody AiConfigSaveDTO dto) {
        return Result.ok(configService.update(id, dto));
    }

    /** DELETE /api/admin/ai/configs/{id} —— 逻辑删除（内置行拒绝：4006） */
    @Operation(summary = "删除（停用保留行）AI 配置")
    @DeleteMapping("/configs/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        configService.delete(id);
        return Result.ok();
    }

    /** PATCH /api/admin/ai/configs/{id}/default —— 设为默认（事务互斥，幂等） */
    @Operation(summary = "设为默认配置")
    @PatchMapping("/configs/{id}/default")
    public Result<Void> setDefault(@PathVariable Long id) {
        configService.setDefault(id);
        return Result.ok();
    }

    /** POST /api/admin/ai/configs/{id}/test —— 连通测试（4101/4102 精确映射，同步等待） */
    @Operation(summary = "配置连通性测试")
    @PostMapping("/configs/{id}/test")
    public Result<TestResult> test(@PathVariable Long id, @AuthenticationPrincipal LoginUser me) {
        return Result.ok(llmFacade.testConnection(id, me.getUserId()));
    }

    /**
     * GET /api/admin/ai/logs —— 调用日志分页（权限点换成 ai:log：与"配置写"解耦，
     * 类级 @PreAuthorize 被方法级注解覆盖，方法级更严即可）。
     * dateFrom/dateTo 用 ISO 格式绑定（yyyy-MM-dd），Service 展开为当日起止时刻。
     */
    @Operation(summary = "AI 调用日志分页查询")
    @GetMapping("/logs")
    @PreAuthorize("hasAuthority('ai:log')")
    public Result<PageResult<AiLogVo>> logs(@RequestParam(defaultValue = "1") long pageNum,
                                            @RequestParam(defaultValue = "20") long pageSize,
                                            @RequestParam(required = false) String bizType,
                                            @RequestParam(required = false) Integer status,
                                            @RequestParam(required = false)
                                            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
                                            @RequestParam(required = false)
                                            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo,
                                            @RequestParam(required = false) Long userId) {
        return Result.ok(callLogService.pageLogs(pageNum, pageSize, bizType, status, dateFrom, dateTo, userId));
    }

    /**
     * GET /api/admin/ai/stats —— 成本统计（M7；答辩演示"AI 成本可视化"的数据口）。
     *
     * <p>窗口参数默认最近 30 天（日志页默认观测量），前端可指定 dateFrom/dateTo；
     * 权限点 ai:log 与日志查询同族（都是"读账本"，与"改配置"的 ai:config 分离）。
     */
    @Operation(summary = "AI 成本统计（按日/按业务/按用户聚合 + 缓存命中率）")
    @GetMapping("/stats")
    @PreAuthorize("hasAuthority('ai:log')")
    public Result<com.blog.module.ai.vo.AiStatsVo> stats(
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo) {
        // 窗口钳制：缺省近 30 天；上界 90 天防爆拉（见 AiCallLogService.stats 注释）。
        // 参数校验取"宽容纠偏"而非报错：统计页是只读看板，日期框被浏览器自动补全
        // 搞出倒挂（from>to）时互换比弹红框友好。
        LocalDate to = dateTo == null ? LocalDate.now() : dateTo;
        LocalDate from = dateFrom == null ? to.minusDays(29) : dateFrom;
        if (from.isAfter(to)) {
            LocalDate tmp = from; from = to; to = tmp;
        }
        if (from.isBefore(to.minusDays(89))) {
            from = to.minusDays(89);
        }
        return Result.ok(callLogService.stats(from, to));
    }
}
