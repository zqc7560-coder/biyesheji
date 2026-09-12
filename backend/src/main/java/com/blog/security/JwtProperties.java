package com.blog.security;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * JWT 相关配置项（绑定 application.yml 的 blog.jwt.*，M0 骨架期已占位对齐命名）。
 *
 * <p>独立成 @ConfigurationProperties 而非散落的 @Value：配置项聚在一处，
 * IDE 可提示、单元测试可直接 set 构造，且 prod 环境强制注入 secret 的约束
 * 只需要看这一个类。
 */
@Data
@Component
@ConfigurationProperties(prefix = "blog.jwt")
public class JwtProperties {

    /**
     * HS256 签名密钥：≥32 字节（jjwt 0.12 对短密钥直接抛 WeakKeyException，
     * 这是防"开发者随手配个 8 位密钥"的硬闸门）。
     * dev 默认值是公开的占位串，生产必须经 BLOG_JWT_SECRET 环境变量注入。
     */
    private String secret;

    /** 令牌有效期秒数：2h 短 TTL（《系统设计.md》D-6 决策：登出矛盾靠 jti 黑名单补齐，
     *  短 TTL 把"黑名单丢失导致已登出令牌复活"的最坏窗口压到 2h） */
    private long expireSeconds = 7200;
}
