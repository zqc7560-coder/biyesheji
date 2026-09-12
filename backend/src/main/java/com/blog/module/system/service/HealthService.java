package com.blog.module.system.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 健康检查服务（阶段二验收项："一条 /api/health 贯通 后端→MySQL→Redis"）。
 *
 * <p>设计要点：
 * 1. 探测逻辑放 Service 而非 Controller——调用链固定 Controller→Service（《系统设计.md》§1），
 *    且后续管理端仪表盘（/api/admin/dashboard）可复用同一探测。
 * 2. MySQL 用 isValid(2s) 而不是执行 SELECT 1：HikariCP 的 isValid 走 JDBC4 原生探测，
 *    更快且不占用语句解析。
 * 3. Redis 探测失败只降级为 DOWN 状态，绝不抛异常——健康接口自身 500 会让
 *    Docker healthcheck 把"依赖挂了"误判成"应用挂了"而无限重启，这是 compose
 *    编排里最常见的连环坑。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HealthService {

    /** 连接池化的数据源：仅用于借一条连接做存活探测 */
    private final DataSource dataSource;

    /** Redis 模板：ping 探测（Lettuce 懒连接，构造该 Bean 不会产生网络 IO） */
    private final StringRedisTemplate stringRedisTemplate;

    /**
     * 汇总各中间件连通态。
     *
     * @return { mysql, redis, overall }，每项取值 UP / DOWN:原因
     */
    public Map<String, Object> check() {
        Map<String, Object> status = new LinkedHashMap<>();
        String mysql = checkMysql();
        String redis = checkRedis();
        status.put("mysql", mysql);
        status.put("redis", redis);
        // overall 用布尔折叠：任一依赖 DOWN 即整体 DOWN，便于 healthcheck 的 jq 断言
        status.put("overall", "UP".equals(mysql) && "UP".equals(redis) ? "UP" : "DOWN");
        return status;
    }

    /** MySQL 探活：借连接验证，超时 2 秒（比接口整体响应预算短，避免拖死调用方） */
    private String checkMysql() {
        try (Connection conn = dataSource.getConnection()) {
            return conn.isValid(2) ? "UP" : "DOWN:connection invalid";
        } catch (Exception e) {
            log.warn("MySQL 健康探测失败: {}", e.getMessage());
            return "DOWN:" + e.getMessage();
        }
    }

    /** Redis 探活：PING 命令往返（SDR 3.x 返回 String "PONG"），异常同样折叠为 DOWN 文案 */
    private String checkRedis() {
        try {
            String pong = stringRedisTemplate.execute((RedisConnection conn) -> conn.ping());
            return "PONG".equalsIgnoreCase(pong) ? "UP" : "DOWN:no reply";
        } catch (Exception e) {
            log.warn("Redis 健康探测失败: {}", e.getMessage());
            return "DOWN:" + e.getMessage();
        }
    }
}
