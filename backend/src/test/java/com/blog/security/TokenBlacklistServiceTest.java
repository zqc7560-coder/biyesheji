package com.blog.security;

import com.blog.support.InMemoryRedis;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TokenBlacklistService 单元测试（纯内存，不依赖 Redis/MySQL/Spring 容器）。
 *
 * <p>锁定两条吊销语义——它们是"JWT 无法服务端吊销"矛盾（《系统设计.md》D-6）的
 * 全部解药，一旦回归，登出/改密/禁号都会静默失效：
 * 1) jti 黑名单：拉黑后 isJtiBlacklisted=true，未拉黑的 jti 不误伤；
 * 2) 用户级失效水位：invalid_before 的读与推进，0=从未被踢。
 */
@DisplayName("令牌吊销缓存: jti黑名单 + 用户级失效水位")
class TokenBlacklistServiceTest {

    private StringRedisTemplate redis;
    private TokenBlacklistService service;

    @BeforeEach
    void setUp() {
        // 每个用例一套全新内存 Redis：测试间零串扰
        redis = InMemoryRedis.template();
        service = new TokenBlacklistService(redis);
    }

    @Test
    @DisplayName("拉黑 jti 后可查到；未拉黑/为 null 的不误伤")
    void blacklistRoundTrip() {
        assertThat(service.isJtiBlacklisted("jti-abc")).isFalse();

        service.blacklistJti("jti-abc", 3600);

        assertThat(service.isJtiBlacklisted("jti-abc")).isTrue();
        assertThat(service.isJtiBlacklisted("jti-xyz")).isFalse();
    }

    @Test
    @DisplayName("剩余有效期 ≤0 的令牌无黑可拉：直接忽略（自然过期即作废）")
    void skipBlacklistForExpiredToken() {
        service.blacklistJti("jti-dead", 0);
        service.blacklistJti(null, 100);

        assertThat(service.isJtiBlacklisted("jti-dead")).isFalse();
    }

    @Test
    @DisplayName("登录快照水位: 初始=登录前1秒；invalidateAll 推进到当前时刻")
    void watermarkLifecycle() {
        // 从未登录过的用户：水位 0（任何令牌都不会被水位误杀）
        assertThat(service.getInvalidBefore(99L)).isZero();

        service.writeLoginSnapshot(7L, "alice", 7200);
        long afterLogin = System.currentTimeMillis();
        // 登录时刻自己签发的令牌必须可用：水位必须严格早于"现在"
        assertThat(service.getInvalidBefore(7L)).isLessThan(afterLogin);

        service.invalidateAllTokens(7L, 7200);
        long afterKick = System.currentTimeMillis();
        // 踢人后水位 ≈ 现在：此前签发的令牌全部落在水位左侧被判失效
        assertThat(service.getInvalidBefore(7L)).isBetween(afterLogin, afterKick + 1000);
    }

    @Test
    @DisplayName("登出清快照：水位回到 0（下一次登录重新计时）")
    void removeSnapshotResets() {
        service.writeLoginSnapshot(8L, "bob", 7200);
        service.invalidateAllTokens(8L, 7200);
        assertThat(service.getInvalidBefore(8L)).isPositive();

        service.removeLoginSnapshot(8L);

        assertThat(service.getInvalidBefore(8L)).isZero();
    }
}
