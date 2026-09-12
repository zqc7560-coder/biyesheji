package com.blog.module.ai.service;

import com.blog.common.BizCode;
import com.blog.common.BizException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/**
 * 用户日配额限流（《AI模块设计.md》§3.4）。
 *
 * <p>键：ai:quota:{uid}:{yyyyMMdd}，INCR 后首次设置 TTL 到当日 24:00——
 * 自然日口径与文案"今日调用次数已达上限"一致，也免去定时清理任务。
 *
 * <p>计什么：只计**真实发起的上游调用**（§3.4 明示）。缓存命中不计数——
 * 命中不花钱，拦它只会伤害正常用户的多标签页/重进编辑器场景；
 * 连通性测试（CONNECT_TEST）也只由管理员触发、成本≈0，不计。
 *
 * <p>Redis 故障策略与 JwtAuthFilter 相反：这里**拒绝放行失败、放行拒绝失败**
 * （fail-open）。理由：AI 是旁路增强，配额是成本护栏而非安全护栏；Redis 抖动时
 * 宁可多放几次调用（最坏损失几厘钱），不能让"AI 按钮"整片报 500 吓到用户。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiQuotaService {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final StringRedisTemplate redis;
    private final AiSysConfigReader configReader;

    /**
     * 前置闸门：超限直接抛 4003（HTTP 429，BizException 携带）。
     * 注意语义是"先查后调"：通过则放行调用，调用完成后 {@link #consume} 才真正记数——
     * 失败调用同样计数（防"每次失败都白重试"式刷爆上游），这是取齐设计文档的取舍。
     */
    public void assertWithinQuota(Long userId) {
        long used = current(userId);
        int quota = configReader.dailyQuota();
        if (used >= quota) {
            throw new BizException(BizCode.AI_QUOTA_EXCEEDED,
                    BizCode.AI_QUOTA_EXCEEDED.getMessage() + "（上限 " + quota + " 次）", 429);
        }
    }

    /** 真实调用发生后的计数（缓存命中路径不调用此方法） */
    public void consume(Long userId) {
        try {
            String key = key(userId);
            Long n = redis.opsForValue().increment(key);
            if (n != null && n == 1L) {
                // 首次计数才设 TTL：到"当日 24:00"的精确秒数（自然日清零，键也自然消失）
                LocalDateTime now = LocalDateTime.now();
                Duration toMidnight = Duration.between(now,
                        LocalDateTime.of(LocalDate.now().plusDays(1), LocalTime.MIDNIGHT));
                redis.expire(key, toMidnight);
            }
        } catch (Exception e) {
            log.warn("AI 配额计数失败（fail-open 放行）: {}", e.getMessage());
        }
    }

    /** 今日已用量（status 接口展示剩余额度 + 前置判定共用）；Redis 异常回 0=当作没用过 */
    public long current(Long userId) {
        try {
            String v = redis.opsForValue().get(key(userId));
            return v == null ? 0 : Long.parseLong(v);
        } catch (Exception e) {
            log.warn("AI 配额读取失败（按 0 处理）: {}", e.getMessage());
            return 0;
        }
    }

    private String key(Long userId) {
        return "ai:quota:" + userId + ":" + LocalDate.now().format(DAY);
    }
}
