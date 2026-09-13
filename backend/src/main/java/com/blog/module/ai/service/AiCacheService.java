package com.blog.module.ai.service;

import com.blog.module.ai.enums.BizType;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;

/**
 * AI 结果缓存（《AI模块设计.md》§6"相同内容不重复调用"）。
 *
 * <p>缓存键五段式：ai:{bizType}:{hash}:{model}:{promptVer}:{temp}
 * 每个维度都回答一个"结果是否可比"的问题：
 * <ul>
 *   <li>hash：内容归一化 SHA-256——任何实质改动（改一个字）都改变哈希；
 *       去空白/去图片语法让"换个缩进、换张图"不触发重新付费；</li>
 *   <li>model：换模型结果风格不同，旧缓存自然绕开（§10 演示脚本步骤 6）；</li>
 *   <li>promptVer：改提示词必须让旧结果失效——经典 bug「改了模板但一直吐旧结果」
 *       的结构性防呆。管理员自定义模板用其内容摘要 cfg-{digest8} 作版本，
 *       比设计文档的 updated_at 更严谨：改无关字段刷新 updated_at 不会误杀缓存，
 *       改模板正文一个字符则必然换键；</li>
 *   <li>temp：温度变了采样结果不可比。</li>
 * </ul>
 *
 * <p>TTL 7 天（§6.2）；失败不写缓存（下次可重试）。Redis 读写整体 try-catch：
 * 缓存是加速器不是正确性依赖——Redis 挂了 AI 照常真实调用（可用性降级路径）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiCacheService {

    /** 结果缓存 TTL：7 天（草稿期反复点按钮的成本防线；过期后重生成可接受） */
    private static final Duration TTL = Duration.ofDays(7);

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    /**
     * 内容归一化哈希（§6.1 公式逐字实现）：去全部空白 → 去图片语法 → 小写，
     * 与标题拼接后取 SHA-256 hex（CHAR(64)，同时进 ai_call_log.content_hash 供审计排查）。
     *
     * <p>已知边界（M11 测试钉死于 AiCacheQuotaM11Test）：Java 的 \s 只覆盖 ASCII 空白，
     * 全角空格 U+3000 不参与归一——与文档公式口径一致；扩公式=全体历史缓存键作废，
     * 属于必须走评审的显式变更，不要顺手改。
     */
    public static String contentHash(String title, String contentMd) {
        String normalized = contentMd.replaceAll("\\s+", "")
                .replaceAll("!\\[[^]]*]\\([^)]*\\)", "")
                .toLowerCase();
        return sha256Hex((title == null ? "" : title) + "\n" + normalized);
    }

    /** 拼五段式键；temperature 统一 scale=2 字符串（0.3 与 0.30 是同一个配置，不能两个键） */
    public String buildKey(BizType bizType, String hash, String model, String promptVer,
                           java.math.BigDecimal temperature) {
        String temp = (temperature == null ? new java.math.BigDecimal("0.30") : temperature)
                .setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
        return "ai:" + bizType.getCode() + ":" + hash + ":" + model + ":" + promptVer + ":" + temp;
    }

    /** 读缓存 JSON；异常/未命中都回 null（调用方按 miss 走真实调用） */
    public String get(String key) {
        try {
            return redis.opsForValue().get(key);
        } catch (Exception e) {
            log.warn("AI 缓存读取失败（按未命中处理）: {}", e.getMessage());
            return null;
        }
    }

    /** 写缓存（SETEX 语义）；失败只留痕不抛——结果已经产生，没必要因缓存故障回滚用户价值 */
    public void put(String key, Object payload) {
        try {
            redis.opsForValue().set(key, objectMapper.writeValueAsString(payload), TTL);
        } catch (Exception e) {
            log.warn("AI 缓存写入失败（下次重新调用）: {}", e.getMessage());
        }
    }

    /** SHA-256 hex（JDK 自带；MessageDigest 非线程安全所以每次现取实例，量小无所谓） */
    private static String sha256Hex(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            // JDK 规范要求 SHA-256 必须存在；真进不来就是 JVM 环境坏了，直接炸比回退 MD5 诚实
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
