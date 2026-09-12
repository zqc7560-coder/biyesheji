package com.blog.support;

import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 测试专用内存版 Redis（M2 认证测试基建）。
 *
 * <p>为什么不堆 @MockBean 桩：TokenBlacklistService 的语义核心是"键存在性 + 读写"，
 * 用 Mockito 逐方法 stub 会把"登出→旧令牌失效"这类跨请求状态流转测成"验证我桩了什么"。
 * 这里用 ConcurrentHashMap 承载真实读写语义（Mockito 只作薄适配器，接口全方法由
 * Mockito 代理默认实现，被用到未支持的命令返回默认值/null 而不炸编译）。
 *
 * <p>expire/TTL 直接吞掉返回 true：测试生命周期远短于 2h，令牌自然过期路径不在
 * 单元层验证（过期语义在 JwtServiceTest 用手工构造过期令牌覆盖）。
 * CI 机器无需启动真实 Redis——进度台账环境约束"测试与外部中间件解耦"。
 */
public final class InMemoryRedis {

    /**
     * 构造接好内存后端的 StringRedisTemplate：
     * opsForValue().set/hasKey 打黑名单键，opsForHash().put/get 打登录快照键。
     */
    @SuppressWarnings("unchecked")
    public static StringRedisTemplate template() {
        // 单表两形态：String 值放 k，Hash 值放 k+"#hash" 的 Map
        Map<String, Object> store = new ConcurrentHashMap<>();

        StringRedisTemplate template = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        HashOperations<String, Object, Object> hashOps = mock(HashOperations.class);

        when(template.opsForValue()).thenReturn(valueOps);
        when(template.opsForHash()).thenReturn(hashOps);

        // set 是 void 方法：必须用 doAnswer 形态（when(...) 对 void 方法不合法）
        org.mockito.Mockito.doAnswer(inv -> store.put(inv.getArgument(0), inv.getArgument(1)))
                .when(valueOps).set(anyString(), anyString());
        org.mockito.Mockito.doAnswer(inv -> store.put(inv.getArgument(0), inv.getArgument(1)))
                .when(valueOps).set(anyString(), anyString(), any(Duration.class));
        // M6 补齐：GET（AI 缓存读/配额读）与 INCR（配额计数）——语义与真 Redis 一致，
        // increment 用 compute 保证原子性（测试里虽然多为单线程，但语义对齐才可信）
        when(valueOps.get(anyString())).thenAnswer(inv -> store.get(inv.<String>getArgument(0)));
        when(valueOps.increment(anyString())).thenAnswer(inv -> {
            String k = inv.getArgument(0);
            Object v = store.compute(k, (key, old) -> {
                long n = old == null ? 0 : Long.parseLong(old.toString());
                return String.valueOf(n + 1);
            });
            return Long.parseLong(v.toString());
        });
        when(template.hasKey(anyString())).thenAnswer(inv -> store.containsKey(inv.<String>getArgument(0)));
        when(template.delete(anyString())).thenAnswer(inv -> {
            String key = inv.getArgument(0);
            Object v = store.remove(key);
            Object h = store.remove(key + "#hash");
            return v != null || h != null;
        });
        when(template.expire(anyString(), any(Duration.class))).thenReturn(true);

        when(hashOps.get(anyString(), any())).thenAnswer(inv -> {
            Map<Object, Object> hash = hash(store, inv.getArgument(0));
            return hash.get(inv.getArgument(1));
        });
        org.mockito.Mockito.doAnswer(inv -> {
            Map<Object, Object> hash = hash(store, inv.getArgument(0));
            hash.put(inv.getArgument(1), inv.getArgument(2));
            return null;
        }).when(hashOps).put(anyString(), any(), any());

        return template;
    }

    private static Map<Object, Object> hash(Map<String, Object> store, String key) {
        @SuppressWarnings("unchecked")
        Map<Object, Object> hash = (Map<Object, Object>)
                store.computeIfAbsent(key + "#hash", k -> new ConcurrentHashMap<>());
        return hash;
    }

    private InMemoryRedis() {
    }
}
