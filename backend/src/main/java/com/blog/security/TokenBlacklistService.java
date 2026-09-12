package com.blog.security;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * 令牌吊销缓存（M2）—— Redis 键空间严格按《数据库设计.md》§4：
 *
 * <pre>
 *   token:blacklist:{jti}   String  TTL=Token 剩余有效期   登出/改密后作废
 *   login:user:{uid}        Hash    TTL=2h                登录快照（含 invalid_before 水位）
 * </pre>
 *
 * <p>为什么需要它（JWT 的根本矛盾，《系统设计.md》D-6）：JWT 无状态 ⇒ 服务端无法吊销
 * 一张仍"验签通过"的令牌。解决方案是短 TTL(2h) + 两级作废位：
 * <ol>
 *   <li><b>jti 黑名单</b>：精确作废一次会话（登出、改密后旧 token）。TTL 取"令牌剩余
 *       有效期"——令牌自然过期后黑名单条目也没有存在意义了，自动清理，内存不涨。</li>
 *   <li><b>用户级失效水位 invalid_before</b>（存在 login:user:{uid} 快照里）：管理员
 *       禁用账号（《API接口设计.md》§8 "禁用即拉黑其登录态"）时要作废该用户<b>所有</b>
 *       端上的令牌，而我们并不登记 jti 清单，所以改判"令牌的签发时间是否早于水位"：
 *       {@code token.iat < invalid_before} ⇒ 视为已失效。这等价于"踢人下线"，
 *       且只需一个键，不需要扫描/维护 jti 集合。</li>
 * </ol>
 *
 * <p>Redis 不可用时的降级策略 = 放行（见 JwtAuthFilter 调用点）：缓存层故障不应该
 * 让整个站点无法登录（宁可承担 2h 内旧令牌仍可用的风险，也不制造全站 500），
 * 这与《部署与测试.md》"依赖故障降级不扩散"的口径一致。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TokenBlacklistService {

    /** jti 黑名单键前缀（设计文档 §4 逐字对齐，便于 redis-cli 排查） */
    private static final String BLACKLIST_PREFIX = "token:blacklist:";
    /** 登录快照键前缀（设计文档 §4 逐字对齐） */
    private static final String LOGIN_SNAPSHOT_PREFIX = "login:user:";
    /** 快照字段：该时刻之前签发的令牌全部作废（"禁用即拉黑登录态"的实现位） */
    private static final String FIELD_INVALID_BEFORE = "invalid_before";

    private final StringRedisTemplate redisTemplate;

    /** 登出/改密：把一次会话的 jti 拉黑，TTL = 令牌剩余秒数（过期即自动清理） */
    public void blacklistJti(String jti, long remainingSeconds) {
        if (jti == null || remainingSeconds <= 0) {
            return; // 已经没有有效期的令牌无需占位
        }
        redisTemplate.opsForValue().set(BLACKLIST_PREFIX + jti, "1",
                Duration.ofSeconds(remainingSeconds));
    }

    /** 该 jti 是否已被作废 */
    public boolean isJtiBlacklisted(String jti) {
        if (jti == null) {
            return false;
        }
        return Boolean.TRUE.equals(redisTemplate.hasKey(BLACKLIST_PREFIX + jti));
    }

    /**
     * 登录成功时写快照（《系统设计.md》§3.1：SET login:user:{userId} = 快照，TTL 2h）。
     *
     * <p>快照保留 username 便于运维肉眼确认"谁在线"；invalid_before 初始化为
     * 登录时刻<b>之前</b>一秒，保证本次新令牌自身可用（判定用严格小于）。
     */
    public void writeLoginSnapshot(Long userId, String username, long ttlSeconds) {
        String key = LOGIN_SNAPSHOT_PREFIX + userId;
        HashOperations<String, Object, Object> hash = redisTemplate.opsForHash();
        long nowMs = System.currentTimeMillis();
        hash.put(key, "username", username);
        hash.put(key, "login_at", String.valueOf(nowMs));
        hash.put(key, FIELD_INVALID_BEFORE, String.valueOf(nowMs - 1000));
        redisTemplate.expire(key, Duration.ofSeconds(ttlSeconds));
    }

    /**
     * 作废某用户水位之前签发的全部令牌（管理员禁用/改密/强制下线，水位=当前毫秒时刻）。
     * 毫秒精度：令牌 iatms 与水位同为毫秒，判定 {@code iatms < 水位} 不存在秒级舍入竞态。
     * 同时刷新 TTL：水位必须在"被作废的旧令牌自然过期"之前一直有效，
     * 而旧令牌最长还能活 expireSeconds，故 TTL 取 2h 正好覆盖最坏窗口。
     */
    public void invalidateAllTokens(Long userId, long ttlSeconds) {
        String key = LOGIN_SNAPSHOT_PREFIX + userId;
        HashOperations<String, Object, Object> hash = redisTemplate.opsForHash();
        hash.put(key, FIELD_INVALID_BEFORE, String.valueOf(System.currentTimeMillis()));
        redisTemplate.expire(key, Duration.ofSeconds(ttlSeconds));
    }

    /** 读取用户失效水位毫秒值（无快照返回 0 = 从未被踢过） */
    public long getInvalidBefore(Long userId) {
        Object raw = redisTemplate.opsForHash().get(LOGIN_SNAPSHOT_PREFIX + userId, FIELD_INVALID_BEFORE);
        if (raw == null) {
            return 0L;
        }
        try {
            return Long.parseLong(String.valueOf(raw));
        } catch (NumberFormatException e) {
            log.warn("login:user:{} 快照 invalid_before 非数字: {}", userId, raw);
            return 0L;
        }
    }

    /** 登出时清理快照（快照是有价值信息，DEL 仅用于"该用户当前无活跃会话"的语义收口） */
    public void removeLoginSnapshot(Long userId) {
        redisTemplate.delete(LOGIN_SNAPSHOT_PREFIX + userId);
    }
}
