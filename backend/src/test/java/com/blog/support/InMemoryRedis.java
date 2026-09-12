package com.blog.support;

import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
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
        // M9 补齐：SetOperations（列表键登记 blog:listkeys / 失效补偿队列 blog:evict:retry）
        org.springframework.data.redis.core.SetOperations<String, String> setOps =
                mock(org.springframework.data.redis.core.SetOperations.class);

        when(template.opsForValue()).thenReturn(valueOps);
        when(template.opsForHash()).thenReturn(hashOps);
        when(template.opsForSet()).thenReturn(setOps);

        // set 是 void 方法：必须用 doAnswer 形态（when(...) 对 void 方法不合法）
        org.mockito.Mockito.doAnswer(inv -> store.put(inv.getArgument(0), inv.getArgument(1)))
                .when(valueOps).set(anyString(), anyString());
        org.mockito.Mockito.doAnswer(inv -> store.put(inv.getArgument(0), inv.getArgument(1)))
                .when(valueOps).set(anyString(), anyString(), any(Duration.class));
        // M9：镜像回填带 TTL 的变体（前三个参数语义一致，Duration 在本内存版里被吞掉——
        // 测试生命周期远短于任何 TTL，过期路径在 JwtServiceTest 用手工过期令牌单独覆盖）
        org.mockito.Mockito.doAnswer(inv -> store.put(inv.getArgument(0), inv.getArgument(1)))
                .when(valueOps).set(anyString(), anyString(), anyLong(), any(java.util.concurrent.TimeUnit.class));
        // M9：single-flight 互斥锁的原子原语（真 Redis 的 SET key val NX EX ttl 在这里是
        // computeIfAbsent——"键不存在才写入"的原子性语义完整保留，锁的取/放/抢失败路径可测）
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenAnswer(inv ->
                store.putIfAbsent(inv.getArgument(0), inv.getArgument(1)) == null);
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
        // M9：带增量的 INCR（restoreCounter 恢复现场用），语义与真 Redis 一致
        when(valueOps.increment(anyString(), anyLong())).thenAnswer(inv -> {
            String k = inv.getArgument(0);
            long d = inv.getArgument(1);
            Object v = store.compute(k, (key, old) -> {
                long n = old == null ? 0 : Long.parseLong(old.toString());
                return String.valueOf(n + d);
            });
            return Long.parseLong(v.toString());
        });
        when(template.hasKey(anyString())).thenAnswer(inv -> store.containsKey(inv.<String>getArgument(0)));
        when(template.delete(anyString())).thenAnswer(inv -> {
            String key = inv.getArgument(0);
            Object v = store.remove(key);
            Object h = store.remove(key + "#hash");
            // M9：#set 分身同步清理（blog:listkeys / blog:evict:retry 的失效都走 delete）
            Object s = store.remove(key + "#set");
            return v != null || h != null || s != null;
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
        // M9 补齐：entries（sys:config 镜像批量读）——返回活引用即可，测试只读不改
        when(hashOps.entries(anyString())).thenAnswer(inv ->
                new java.util.HashMap<>(hash(store, inv.getArgument(0))));
        // M9 补齐：Hash 写整表（镜像回填用 putAll 省逐字段打桩）
        org.mockito.Mockito.doAnswer(inv -> {
            Map<Object, Object> hash = hash(store, inv.getArgument(0));
            hash.putAll(inv.getArgument(1));
            return null;
        }).when(hashOps).putAll(anyString(), any(Map.class));

        // M9 补齐：Set 族（blog:listkeys 登记 / blog:evict:retry 补偿队列）
        org.mockito.Mockito.doAnswer(inv -> {
            java.util.Set<Object> set = set(store, inv.getArgument(0));
            Object[] all = inv.getArguments();
            for (int i = 1; i < all.length; i++) {
                set.add(all[i]);
            }
            return (Long) (long) set.size();
        }).when(setOps).add(anyString(), any(String[].class));
        when(setOps.members(anyString())).thenAnswer(inv ->
                new java.util.HashSet<>(set(store, inv.getArgument(0))));
        org.mockito.Mockito.doAnswer(inv -> {
            java.util.Set<Object> set = set(store, inv.getArgument(0));
            Object[] all = inv.getArguments();
            long removed = 0;
            for (int i = 1; i < all.length; i++) {
                if (set.remove(all[i])) {
                    removed++;
                }
            }
            return removed;
        }).when(setOps).remove(anyString(), any(Object[].class));
        when(setOps.size(anyString())).thenAnswer(inv ->
                (long) set(store, inv.getArgument(0)).size());

        // M9 补齐：keys(pattern)——把 glob 翻译成正则做全键匹配（详情/列表/PV 键扫描与清缓存口）。
        // 只支持 "*abc*" 通配：本项目全部调用点都是"前缀+*"形态，够用且行为可预期
        when(template.keys(anyString())).thenAnswer(inv -> {
            String glob = inv.getArgument(0);
            java.util.regex.Pattern p = java.util.regex.Pattern.compile(
                    glob.chars().mapToObj(c -> c == '*' ? ".*" : (c == '?' ? "." :
                            java.util.regex.Pattern.quote(String.valueOf((char) c))))
                            .collect(java.util.stream.Collectors.joining()));
            java.util.Set<String> out = new java.util.HashSet<>();
            for (String k : store.keySet()) {
                // #hash/#set 后缀是聚合结构在单表里的分身，不是独立键，不参与模式匹配
                int cut = k.indexOf("#");
                if (cut < 0 ? p.matcher(k).matches()
                        : p.matcher(k.substring(0, cut)).matches()) {
                    out.add(cut < 0 ? k : k.substring(0, cut));
                }
            }
            return out;
        });

        return template;
    }

    private static Map<Object, Object> hash(Map<String, Object> store, String key) {
        @SuppressWarnings("unchecked")
        Map<Object, Object> hash = (Map<Object, Object>)
                store.computeIfAbsent(key + "#hash", k -> new ConcurrentHashMap<>());
        return hash;
    }

    /** M9：Set 型键的内存分身（与 #hash 同理——ConcurrentHashMap 的 keySet 当 Set 用） */
    @SuppressWarnings("unchecked")
    private static java.util.Set<Object> set(Map<String, Object> store, String key) {
        return (java.util.Set<Object>)
                store.computeIfAbsent(key + "#set", k -> java.util.concurrent.ConcurrentHashMap.newKeySet());
    }

    private InMemoryRedis() {
    }
}
