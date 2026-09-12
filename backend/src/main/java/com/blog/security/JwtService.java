package com.blog.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;

/**
 * JWT 签发与验签（M2 核心，jjwt 0.12 API）。
 *
 * <p>令牌载荷设计（《系统设计.md》§3.1：payload userId/username/role/jti）在骨架
 * 基础上扩展为 claims：uid / nickname / avatar / roles[] / perms[] / jti。
 * 把角色与权限点放进令牌是"每请求零查库"的关键：验签即可构造完整 SecurityContext。
 * 单令牌体积 <2KB（10 个权限点量级），远低于浏览器 header 上限，安全边界见
 * LoginUser 注释（权限变更需重登生效）。
 *
 * <p>jti（JWT ID）：每次登录随机 UUID，是"服务端吊销"能力的锚点——登出/改密时
 * 按 jti 写 Redis 黑名单（《系统设计.md》§3.1 登出时序），令牌本身仍"验签通过"
 * 但命中黑名单即拒（code=1001）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtService {

    private final JwtProperties jwtProperties;

    /** 过期异常语义化包装：过滤器据此区分 1004（过期，可静默刷新）与 1001（其他失效） */
    public static class TokenExpiredException extends RuntimeException {
        public TokenExpiredException(String msg) { super(msg); }
    }

    /** 验签失败/格式非法/被篡改：统一映射 1001 */
    public static class TokenInvalidException extends RuntimeException {
        public TokenInvalidException(String msg) { super(msg); }
    }

    /** 惰性缓存的签名 Key：配置不变时避免每次请求都从字符串重建密钥对象 */
    private volatile SecretKey cachedKey;

    /** HS256 密钥：配置字符串的 UTF-8 字节直接作 key（≥32B 由配置纪律保证，
     *  短于 32B 时 jjwt 抛 WeakKeyException 快速暴露配置错误） */
    private SecretKey key() {
        SecretKey k = cachedKey;
        if (k == null) {
            synchronized (this) {
                k = cachedKey;
                if (k == null) {
                    k = new javax.crypto.spec.SecretKeySpec(
                            jwtProperties.getSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256");
                    cachedKey = k;
                }
            }
        }
        return k;
    }

    /**
     * 签发访问令牌。
     *
     * @param userId  用户 ID（claim: uid）
     * @param username 登录名（subject 用登录名，便于日志阅读）
     * @param nickname 昵称（me 接口/前端头像区回显，免二次查库）
     * @param avatar   头像 URL
     * @param roles    角色码集合（ROLE_USER/ROLE_ADMIN）
     * @param perms    权限点集合（article:save...）
     * @return 签名后的 compact JWT
     */
    public String issue(Long userId, String username, String nickname, String avatar,
                        List<String> roles, List<String> perms) {
        Instant now = Instant.now();
        Instant exp = now.plusSeconds(jwtProperties.getExpireSeconds());
        return Jwts.builder()
                .id(UUID.randomUUID().toString())          // jti：登出黑名单锚点
                .subject(username)
                .claim("uid", userId)
                // 毫秒级签发时刻：失效水位判定专用（标准 iat 只有秒精度，
                // "改密后同秒重登"会被秒级水位误杀或漏杀，见 TokenBlacklistService）
                .claim("iatms", System.currentTimeMillis())
                .claim("nickname", nickname)
                .claim("avatar", avatar)
                .claim("roles", roles)
                .claim("perms", perms)
                .issuedAt(Date.from(now))
                .expiration(Date.from(exp))
                .signWith(key(), Jwts.SIG.HS256)
                .compact();
    }

    /**
     * 验签并解析令牌为 claims。
     *
     * @throws TokenExpiredException 过期（调用方转 1004）
     * @throws TokenInvalidException 验签失败/格式非法（调用方转 1001）
     */
    public Claims parse(String token) {
        try {
            return Jwts.parser()
                    .verifyWith(key())
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
        } catch (ExpiredJwtException e) {
            throw new TokenExpiredException("token expired");
        } catch (JwtException | IllegalArgumentException e) {
            throw new TokenInvalidException("token invalid: " + e.getMessage());
        }
    }

    /**
     * 从 claims 构造登录态对象（nickname/avatar/roles/perms 都取自令牌声明，零查库）。
     *
     * @param remainingSeconds 令牌剩余有效期：登出拉黑时的 Redis TTL
     */
    public LoginUser toLoginUser(Claims claims, long remainingSeconds) {
        return new LoginUser(
                claims.get("uid", Number.class).longValue(),
                claims.getSubject(),
                claims.get("nickname", String.class),
                claims.get("avatar", String.class),
                asStringList(claims.get("roles")),
                asStringList(claims.get("perms")),
                claims.getId(),
                remainingSeconds,
                issuedAtMs(claims));
    }

    /** 取毫秒签发时刻：优先 iatms 声明；历史令牌缺失时回退标准 iat×1000（秒精度容忍） */
    public static long issuedAtMs(Claims claims) {
        Number raw = claims.get("iatms", Number.class);
        return raw != null ? raw.longValue() : claims.getIssuedAt().getTime();
    }

    /**
     * jjwt 把 JSON 数组解成 List<Object>；null 安全折叠成字符串列表
     * （手工构造/老令牌缺 claim 时返回空集而非 NPE）。
     */
    private List<String> asStringList(Object raw) {
        if (raw instanceof List<?> list) {
            return list.stream().map(String::valueOf).toList();
        }
        return List.of();
    }
}
