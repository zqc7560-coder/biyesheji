package com.blog.module.ai.vo;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * AI 成本统计出参（GET /api/admin/ai/stats 的 data；《API接口设计.md》§7 的
 * `{ byDay, byBiz, cacheHitRate, todayCost }` 契约 + 用户维度扩展）。
 *
 * <p>为什么聚合在 Java 侧做（把明细行拉回来 stream groupingBy）而不是 GROUP BY SQL：
 * ① M4 实测教训——跨库 SQL 方言（DATE()/DATE_FORMAT、列标签大小写）是 H2 测试与
 * MySQL 生产行为漂移的高发区，聚合下沉到 Java 后两库跑的是同一份语义；
 * ② ai_call_log 毕设量级（数千行）拉明细的代价可忽略；
 * ③ 日志表 append-only 无并发写干扰，读时聚合的正确性与预聚合等价。
 */
@Data
@Builder
public class AiStatsVo {

    /** 统计窗口起点（含），ISO 日期；前端标题展示 */
    private String dateFrom;
    /** 统计窗口终点（含） */
    private String dateTo;

    /** 按日汇总（日期降序）；每行固定键：date/calls/success/failures/cacheHits/cost */
    private List<Map<String, Object>> byDay;

    /** 按业务类型汇总：键=SUMMARY/TAGS/CONNECT_TEST，值含 calls/cacheHits/cost/avgLatencyMs */
    private Map<String, Map<String, Object>> byBiz;

    /** 按用户汇总的成本 Top10（答辩"谁在用 AI"）：userId/nickname/calls/cost */
    private List<Map<String, Object>> topUsers;

    /** 窗口内整体缓存命中率（0~1，保留 4 位；§6.3 目标 ≥30% 的观测值） */
    private BigDecimal cacheHitRate;

    /** 今日真实调用总成本（元）——与窗口无关的固定"今天"口径，仪表盘主数字 */
    private BigDecimal todayCost;

    /** 窗口内总调用次数（含缓存命中），给前端做"命中率分母"提示 */
    private long totalCalls;
}
