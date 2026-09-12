package com.blog.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 安全相关基础 Bean（M2 认证模块）。
 *
 * <p>为什么单独拆一个配置类而不是塞进 SecurityConfig：PasswordEncoder（BCrypt 散列器）
 * 是业务 Service（注册/改密/AdminBootstrap）与 Spring Security 过滤器链都要依赖的
 * 纯工具 Bean，与"链路如何鉴权"的 SecurityFilterChain 关注点不同。拆开可以避免
 * 业务类 import 整个 SecurityConfig 造成配置耦合，也让单元测试可以只 new 这个
 * 轻量配置来取散列器。
 *
 * <p>BCrypt 选型依据《系统设计.md》§4：自带随机盐（同明文两次散列不同密文，天然
 * 防彩虹表与"相同密码=相同密文"的泄露），cost=10（默认强度）在 2026 年的
 * 服务器上是 ~60ms/次，登录 QPS 场景可接受；密码列 VARCHAR(100) 已为
 * "$2a$10$ + 53 字符散列体 = 60 字符"留足余量（《数据库设计.md》§3.1）。
 */
@Configuration
public class SecurityBeansConfig {

    /** 全局唯一的密码散列器：注册写入、登录 matches 校验都走它 */
    @Bean
    public PasswordEncoder passwordEncoder() {
        // 用 strength=10 的默认 BCrypt；不用 BCryptPasswordEncoder() 裸构造是为了
        // 显式写出强度值，方便日后按 GPU 算力演进统一调参（改一处生效全站）
        return new BCryptPasswordEncoder(10);
    }
}
