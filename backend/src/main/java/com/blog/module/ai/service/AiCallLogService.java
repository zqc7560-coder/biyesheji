package com.blog.module.ai.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.blog.common.PageResult;
import com.blog.module.ai.entity.AiCallLog;
import com.blog.module.ai.enums.BizType;
import com.blog.module.ai.mapper.AiCallLogMapper;
import com.blog.module.ai.vo.AiLogVo;
import com.blog.module.user.entity.SysUser;
import com.blog.module.user.mapper.SysUserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * AI 调用记账（《AI模块设计.md》§6.3 / WBS 1.6.7）—— 答辩数据（成功率/命中率/P95）
 * 的唯一写入方。
 *
 * <p>append-only：只提供 insert 与 select，没有任何 update/delete 通道
 * （实体连 updated_at/deleted 列都没有，见 AiCallLog 类注释）。改一行日志
 * 等于篡改统计口径。
 *
 * <p>记账的失败隔离铁律：日志写失败绝不允许影响 AI 结果返回——
 * 所有 record 内部 try-catch 吞异常只留 warn。理由：AI 调用已经花了钱、
 * 结果已经产生，因"审计写不进"而给用户报 500 是本末倒置；
 * 日志缺失可以事后从应用日志补，用户体验缺失不能。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiCallLogService {

    /** error_msg 列宽 500；上游响应体可能很长且含敏感回显，先脱敏再截断 */
    private static final int ERROR_MSG_MAX = 500;

    private final AiCallLogMapper logMapper;
    private final SysUserMapper userMapper;
    private final AiSysConfigReader configReader;

    /**
     * 记一次成功调用。
     *
     * @param status 1 直接成功 / 3 重试后成功（retryCount>0 时传 3，对齐 §3.12 状态语义）
     */
    public void recordSuccess(Long userId, BizType bizType, Long configId, String model,
                              Integer promptTokens, Integer completionTokens,
                              long latencyMs, int retryCount, boolean cacheHit, String contentHash) {
        AiCallLog row = base(userId, bizType, configId, model, latencyMs, cacheHit, contentHash);
        row.setStatus(cacheHit ? 1 : (retryCount > 0 ? 3 : 1));
        row.setRetryCount(retryCount);
        row.setPromptTokens(promptTokens);
        row.setCompletionTokens(completionTokens);
        // 缓存命中成本恒 0（设计明示"审计成本归零"）；真实调用按 sys_config 单价估算
        row.setCostEst(cacheHit ? BigDecimal.ZERO : estimateCost(promptTokens, completionTokens));
        insertQuietly(row);
    }

    /** 记一次失败调用（error_msg 脱敏+截断；tokens 未知留 NULL） */
    public void recordFailure(Long userId, BizType bizType, Long configId, String model,
                              long latencyMs, int retryCount, String errorMsg, String contentHash) {
        AiCallLog row = base(userId, bizType, configId, model, latencyMs, false, contentHash);
        row.setStatus(2);
        row.setRetryCount(retryCount);
        row.setErrorMsg(sanitize(errorMsg));
        insertQuietly(row);
    }

    /** 管理端日志分页（GET /api/admin/ai/logs）：nickname 批量回填（一页 ≤100 用户，两查代替联表，ORM 一致性好） */
    public PageResult<AiLogVo> pageLogs(long pageNum, long pageSize, String bizType,
                                        Integer status, LocalDate dateFrom, LocalDate dateTo, Long userId) {
        LambdaQueryWrapper<AiCallLog> w = new LambdaQueryWrapper<AiCallLog>()
                .eq(bizType != null && !bizType.isBlank(), AiCallLog::getBizType, bizType)
                .eq(status != null, AiCallLog::getStatus, status)
                .eq(userId != null, AiCallLog::getUserId, userId)
                .ge(dateFrom != null, AiCallLog::getCreatedAt,
                        dateFrom == null ? null : dateFrom.atStartOfDay())
                .le(dateTo != null, AiCallLog::getCreatedAt,
                        dateTo == null ? null : dateTo.atTime(23, 59, 59))
                .orderByDesc(AiCallLog::getId);
        Page<AiCallLog> p = logMapper.selectPage(new Page<>(pageNum, Math.min(pageSize, 100)), w);
        List<AiCallLog> records = p.getRecords();

        Map<Long, String> nicknames = Map.of();
        if (!records.isEmpty()) {
            List<Long> uids = records.stream().map(AiCallLog::getUserId).distinct().toList();
            nicknames = userMapper.selectBatchIds(uids).stream()
                    .collect(Collectors.toMap(SysUser::getId, SysUser::getNickname, (a, b) -> a));
        }
        final Map<Long, String> nameMap = nicknames;
        List<AiLogVo> vos = records.stream().map(r -> AiLogVo.from(r, nameMap.get(r.getUserId()))).toList();
        return new PageResult<>(p.getTotal(), p.getPages(), p.getCurrent(), p.getSize(), vos);
    }

    private AiCallLog base(Long userId, BizType bizType, Long configId, String model,
                           long latencyMs, boolean cacheHit, String contentHash) {
        AiCallLog row = new AiCallLog();
        row.setUserId(userId);
        row.setBizType(bizType.name());
        row.setAiConfigId(configId == null ? 0L : configId);   // NOT NULL 列：理论不发生，0=哨兵值防插入失败
        row.setModel(model == null ? "unknown" : model);
        row.setLatencyMs((int) Math.min(latencyMs, Integer.MAX_VALUE));
        row.setCacheHit(cacheHit ? 1 : 0);
        row.setRetryCount(0);
        row.setContentHash(contentHash);
        row.setCreatedAt(LocalDateTime.now());
        return row;
    }

    /** 成本估算（元）：tokens/1000 × 单价，DECIMAL(10,6) 保留 6 位（厘分之下仍有意义，账单审计粒度） */
    private BigDecimal estimateCost(Integer promptTokens, Integer completionTokens) {
        BigDecimal p = promptTokens == null ? BigDecimal.ZERO
                : configReader.pricePer1kPrompt().multiply(BigDecimal.valueOf(promptTokens))
                        .divide(BigDecimal.valueOf(1000), 6, RoundingMode.HALF_UP);
        BigDecimal c = completionTokens == null ? BigDecimal.ZERO
                : configReader.pricePer1kCompletion().multiply(BigDecimal.valueOf(completionTokens))
                        .divide(BigDecimal.valueOf(1000), 6, RoundingMode.HALF_UP);
        return p.add(c);
    }

    private void insertQuietly(AiCallLog row) {
        try {
            logMapper.insert(row);
        } catch (Exception e) {
            log.warn("AI 调用日志写入失败（不影响业务返回）: {}", e.getMessage());
        }
    }

    /** 失败信息脱敏：厂商错误体可能回显 Key（sk-…），统一替换后再截断存列 */
    private String sanitize(String msg) {
        if (msg == null) {
            return null;
        }
        String s = msg.replaceAll("sk-[A-Za-z0-9._-]{4,}", "sk-****");
        return s.length() > ERROR_MSG_MAX ? s.substring(0, ERROR_MSG_MAX) : s;
    }
}
