package com.blog.security;

import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * JwtService 纯单元测试（不起 Spring 容器：手工 new 依赖即可，跑得比眨眼快）。
 *
 * <p>覆盖签发↔验签往返、声明完整性（uid/roles/perms/jti/iatms）、
 * 三类失败路径：篡改签名(1001)、格式垃圾(1001)、自然过期(1004)——
 * 1001/1004 的区分是前端"跳登录 vs 静默刷新"契约的根，必须锁死。
 */
@DisplayName("JWT 签发验签: 往返 + 篡改 + 过期")
class JwtServiceTest {

    /** ≥32 字节的测试密钥（与 dev 配置同纪律；长度不足 jjwt 直接拒签） */
    private static final String SECRET = "unit-test-secret-0123456789-abcdefgh-ijk";

    private JwtService serviceWith(long expireSeconds) {
        JwtProperties props = new JwtProperties();
        props.setSecret(SECRET);
        props.setExpireSeconds(expireSeconds);
        return new JwtService(props);
    }

    @Test
    @DisplayName("往返：签出的令牌验签后 claims 完整无损")
    void issueAndParseRoundTrip() {
        JwtService jwt = serviceWith(7200);

        String token = jwt.issue(42L, "alice", "爱丽丝", "/uploads/a.png",
                List.of("ROLE_USER"), List.of("article:save", "ai:use"));
        Claims claims = jwt.parse(token);

        assertThat(claims.get("uid", Number.class).longValue()).isEqualTo(42L);
        assertThat(claims.getSubject()).isEqualTo("alice");
        assertThat(claims.getId()).isNotBlank();                       // jti 已生成
        assertThat(claims.get("roles", Object.class)).asString().contains("ROLE_USER");
        assertThat(claims.get("nickname", String.class)).isEqualTo("爱丽丝"); // 中文声明不乱码
        assertThat(claims.getExpiration().getTime())
                .isGreaterThan(claims.getIssuedAt().getTime() + 7100_000); // TTL ≈ 2h

        LoginUser user = jwt.toLoginUser(claims, 7000);
        assertThat(user.getUserId()).isEqualTo(42L);
        assertThat(user.getNickname()).isEqualTo("爱丽丝");
        assertThat(user.getRoles()).containsExactly("ROLE_USER");
        assertThat(user.getPerms()).containsExactly("article:save", "ai:use");
        assertThat(user.getJti()).isEqualTo(claims.getId());
        assertThat(user.getTokenRemainingSeconds()).isEqualTo(7000);
    }

    @Test
    @DisplayName("iatms 毫秒声明存在且与签发时刻一致（失效水位判定的地基）")
    void millisecondIssuedAtClaim() {
        JwtService jwt = serviceWith(7200);
        long before = System.currentTimeMillis();

        String token = jwt.issue(1L, "u", "n", "a", List.of("ROLE_USER"), List.of());
        Claims claims = jwt.parse(token);

        long iatms = JwtService.issuedAtMs(claims);
        assertThat(iatms).isBetween(before, System.currentTimeMillis() + 1000);
    }

    @Test
    @DisplayName("篡改签名 → TokenInvalidException（对应 1001）")
    void tamperedSignatureRejected() {
        JwtService jwt = serviceWith(7200);
        String token = jwt.issue(1L, "u", "n", null, List.of("ROLE_ADMIN"), List.of("ai:config"));

        // 改掉最后一个字符：签名校验必然失败
        String tampered = token.substring(0, token.length() - 2) + "xx";

        assertThatThrownBy(() -> jwt.parse(tampered))
                .isInstanceOf(JwtService.TokenInvalidException.class);
    }

    @Test
    @DisplayName("垃圾字符串 / 缺段令牌 → TokenInvalidException 而不是 NPE")
    void garbageTokenRejected() {
        JwtService jwt = serviceWith(7200);

        assertThatThrownBy(() -> jwt.parse("not.a.jwt"))
                .isInstanceOf(JwtService.TokenInvalidException.class);
        assertThatThrownBy(() -> jwt.parse(""))
                .isInstanceOf(JwtService.TokenInvalidException.class);
    }

    @Test
    @DisplayName("过期令牌 → TokenExpiredException（对应 1004，与 1001 分流）")
    void expiredTokenGetsDistinctException() {
        JwtService jwt = serviceWith(-10); // TTL 负数：签出来即过期

        String token = jwt.issue(1L, "u", "n", null, List.of(), List.of());

        assertThatThrownBy(() -> jwt.parse(token))
                .isInstanceOf(JwtService.TokenExpiredException.class);
    }

    @Test
    @DisplayName("密钥不足 256bit → 拒签（WeakKeyException 快速暴露配置事故）")
    void weakSecretFailsFast() {
        // 另建实例（不能复用 serviceWith：其密钥固定合规；且密钥在实例内惰性缓存）
        JwtProperties props = new JwtProperties();
        props.setSecret("short"); // 故意 <32 字节 = HS256 最低 256bit 要求
        props.setExpireSeconds(7200);
        JwtService jwt = new JwtService(props);

        assertThatThrownBy(() -> jwt.issue(1L, "u", "n", null, List.of(), List.of()))
                .isInstanceOf(RuntimeException.class); // jjwt 抛 WeakenedKeyException
    }
}
