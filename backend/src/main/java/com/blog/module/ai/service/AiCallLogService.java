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

    /**
     * M7 成本统计（GET /api/admin/ai/stats 的数据源）：拉窗口内明细，Java 侧聚合。
     *
     * <p>为什么不写 GROUP BY SQL：见 AiStatsVo 类注释（跨库方言漂移是 H2/MySQL
     * 双库测试矩阵的头号敌人；聚合语义放 Java 一遍过）。窗口上限由调用方钳到
     * 90 天——日志量再大也只拉 90 天，防止"全表拉回来数数"的退化。
     */
    public com.blog.module.ai.vo.AiStatsVo stats(LocalDate from, LocalDate to) {
        List<AiCallLog> rows = logMapper.selectList(new LambdaQueryWrapper<AiCallLog>()
                .select(AiCallLog::getUserId, AiCallLog::getBizType, AiCallLog::getCacheHit,
                        AiCallLog::getStatus, AiCallLog::getCostEst, AiCallLog::getLatencyMs,
                        AiCallLog::getCreatedAt)
                .ge(AiCallLog::getCreatedAt, from.atStartOfDay())
                .le(AiCallLog::getCreatedAt, to.atTime(23, 59, 59)));

        LocalDate today = LocalDate.now();

        // ① 按日聚合（TreeMap 逆序键 → 日期降序输出）
        java.util.Map<LocalDate, long[]> byDayAcc = new java.util.TreeMap<>(java.util.Comparator.reverseOrder());
        // ② 按 biz 聚合：calls/cacheHits/cost(微元整数累加避免 BigDecimal 相加噪声)/latency 和
        java.util.Map<String, Object[]> byBizAcc = new java.util.LinkedHashMap<>();
        // ③ 按用户聚合：calls/cost
        java.util.Map<Long, long[]> byUserAcc = new java.util.HashMap<>();
        long hit = 0;
        java.math.BigDecimal todayCost = BigDecimal.ZERO;

        for (AiCallLog r : rows) {
            LocalDate d = r.getCreatedAt().toLocalDate();
            long[] day = byDayAcc.computeIfAbsent(d, k -> new long[5]);   // calls/success/fail/cacheHit/costNano
            day[0]++;
            if (r.getStatus() != null && (r.getStatus() == 1 || r.getStatus() == 3)) day[1]++;
            if (r.getStatus() != null && r.getStatus() == 2) day[2]++;
            boolean cacheHit = r.getCacheHit() != null && r.getCacheHit() == 1;
            if (cacheHit) { day[3]++; hit++; }
            java.math.BigDecimal cost = r.getCostEst() == null ? BigDecimal.ZERO : r.getCostEst();
            day[4] += cost.multiply(BILLION).longValue();
            if (d.equals(today)) todayCost = todayCost.add(cost);

            Object[] biz = byBizAcc.computeIfAbsent(r.getBizType(),
                    k -> new Object[]{0L, 0L, BigDecimal.ZERO, 0L});
            biz[0] = (Long) biz[0] + 1;
            if (cacheHit) biz[1] = (Long) biz[1] + 1;
            biz[2] = ((java.math.BigDecimal) biz[2]).add(cost);
            biz[3] = (Long) biz[3] + (r.getLatencyMs() == null ? 0 : r.getLatencyMs());

            long[] user = byUserAcc.computeIfAbsent(r.getUserId() == null ? 0L : r.getUserId(),
                    k -> new long[2]);   // calls/costNano
            user[0]++;
            user[1] += cost.multiply(BILLION).longValue();
        }

        // 昵称回填：只对成本 Top10 的用户查（全量查会把 Top10 之外的行也拖进 IN 子句）
        List<Map<String, Object>> topUsers = byUserAcc.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue()[1], a.getValue()[1]))
                .limit(10)
                .map(e -> {
                    Map<String, Object> m = new java.util.LinkedHashMap<>();
                    m.put("userId", e.getKey());
                    m.put("calls", e.getValue()[0]);
                    m.put("cost", nanoToYuan(e.getValue()[1]));
                    return m;
                })
                .collect(java.util.stream.Collectors.toList());
        if (!topUsers.isEmpty()) {
            List<Long> uids = topUsers.stream()
                    .map(m -> ((Number) m.get("userId")).longValue()).toList();
            Map<Long, String> names = userMapper.selectBatchIds(uids).stream()
                    .collect(Collectors.toMap(SysUser::getId, SysUser::getNickname, (a, b) -> a));
            topUsers.forEach(m -> m.put("nickname",
                    names.getOrDefault(((Number) m.get("userId")).longValue(), "已注销用户")));
        }

        List<Map<String, Object>> byDay = byDayAcc.entrySet().stream().map(e -> {
            Map<String, Object> m = new java.util.LinkedHashMap<String, Object>();
            m.put("date", e.getKey().toString());
            m.put("calls", e.getValue()[0]);
            m.put("success", e.getValue()[1]);
            m.put("failures", e.getValue()[2]);
            m.put("cacheHits", e.getValue()[3]);
            m.put("cost", nanoToYuan(e.getValue()[4]));
            return m;
        }).collect(Collectors.toList());

        Map<String, Map<String, Object>> byBiz = new java.util.LinkedHashMap<>();
        byBizAcc.forEach((biz, acc) -> {
            Map<String, Object> m = new java.util.LinkedHashMap<>();
            long calls = (Long) acc[0];
            m.put("calls", calls);
            m.put("cacheHits", acc[1]);
            m.put("cost", acc[2]);
            m.put("avgLatencyMs", calls == 0 ? 0 : (Long) acc[3] / calls);
            byBiz.put(biz, m);
        });

        return com.blog.module.ai.vo.AiStatsVo.builder()
                .dateFrom(from.toString()).dateTo(to.toString())
                .byDay(byDay).byBiz(byBiz).topUsers(topUsers)
                // 命中率保留 4 位小数（0.4231 形态；分母含缓存命中行——命中率=命中/全部请求）
                .cacheHitRate(java.math.BigDecimal.valueOf(rows.isEmpty() ? 0.0 : (double) hit / rows.size())
                        .setScale(4, java.math.RoundingMode.HALF_UP))
                .todayCost(todayCost)
                .totalCalls(rows.size())
                .build();
    }

    /** 元 → 纳元的乘数：聚合期用 long 累加（BigDecimal 逐个 add 在 Java 侧既慢又易引入 scale 漂移），出口再除回来 */
    private static final java.math.BigDecimal BILLION = new java.math.BigDecimal("1000000000");

    private static java.math.BigDecimal nanoToYuan(long nano) {
        return java.math.BigDecimal.valueOf(nano).divide(BILLION, 6, java.math.RoundingMode.HALF_UP);
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
