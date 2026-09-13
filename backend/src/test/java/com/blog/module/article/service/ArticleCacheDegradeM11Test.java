package com.blog.module.article.service;

import com.blog.module.article.vo.ArticleVO;
import com.blog.support.InMemoryRedis;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * M11 测试完善 · ArticleCacheService 降级矩阵与双删语义单测。
 *
 * <p>M9 的 CacheApiM9Test 在"缓存健康"的世界线里钉了读/写/失效/回写主干；
 * 本类专测它的反面——<b>Redis 半死不活时每一个方法必须怎样表现</b>。这是类注释
 * "缓存是加速器不是正确性依赖"铁律的行为学证明：JaCoCo 显示该类的缺口行几乎
 * 全部是 catch 分支（40/404 行），而每一个没被扳机过的 catch 都是一个"生产首次
 * 触发即首次执行"的暗雷。用 mock 强制 opsForValue()/keys() 抛异常即可逐行引爆。
 *
 * <p>另钉 evictTx 的 afterCommit 第二删（双删竞态防线 D-5）：用
 * TransactionSynchronizationManager 手工开同步上下文，驱动注册回调验证触发。
 */
@DisplayName("M11 文章缓存: Redis故障逐方法降级/双删回调/载荷回环")
class ArticleCacheDegradeM11Test {

    private final ObjectMapper om = new ObjectMapper();

    /** 一切 Redis 调用都炸的模板（opsForValue/opsForSet 抛、delete/keys/expire 抛） */
    private static StringRedisTemplate boomRedis() {
        StringRedisTemplate t = mock(StringRedisTemplate.class);
        when(t.opsForValue()).thenThrow(new RuntimeException("redis down"));
        when(t.opsForSet()).thenThrow(new RuntimeException("redis down"));
        when(t.keys(anyString())).thenThrow(new RuntimeException("redis down"));
        when(t.delete(java.util.List.of())).thenThrow(new RuntimeException("redis down"));
        Mockito.<Boolean>when(t.delete(anyString())).thenThrow(new RuntimeException("redis down"));
        Mockito.<Boolean>when(t.expire(anyString(), Mockito.any())).thenThrow(new RuntimeException("redis down"));
        return t;
    }

    private ArticleVO sampleVo() {
        ArticleVO vo = new ArticleVO();
        vo.setId(1L);
        vo.setTitle("缓存载荷回环标题");
        vo.setStatus(1);
        return vo;
    }

    // ==================== 故障世界线：每个方法各就各位地"失败得体面" ====================

    @Test
    @DisplayName("读族降级：getDetail/getList 故障按 miss 回 null、peek 族回 0、scanKeys 回空集")
    void readPathsDegradeQuietly() {
        ArticleCacheService svc = new ArticleCacheService(boomRedis(), om);

        assertThat(svc.getDetail(1L)).isNull();
        assertThat(svc.getList("blog:list:x")).isNull();
        assertThat(svc.scanKeys("blog:pv:*")).isEmpty();
        assertThat(svc.peekCounter("blog:pv:1")).isZero();
        assertThat(svc.peekDayPv("20260913")).isZero();
    }

    @Test
    @DisplayName("写族降级：putDetail/putList/countPv/restoreCounter/refreshDayPvTtl 故障全静默（不抛=不炸请求）")
    void writePathsSwallowFailures() {
        ArticleCacheService svc = new ArticleCacheService(boomRedis(), om);

        assertThatCode(() -> {
            svc.putDetail(1L, sampleVo());
            svc.putList("blog:list:x", new ArticleCacheService.CachePage(0, 0, 1, 10, java.util.List.of()));
            svc.countPv(1L);
            svc.restoreCounter("blog:pv:1", 5L);
            svc.refreshDayPvTtl();
        }).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("evict 故障 → 进补偿队列；队列也写不进 → 仍不抛（TTL 是最后防线，日志不风暴）")
    void evictFallsBackToRetryQueue() {
        // 第一层：delete 炸但 opsForSet 可用 → 键进 EVICT_RETRY
        StringRedisTemplate semi = InMemoryRedis.template();
        StringRedisTemplate flaky = mock(StringRedisTemplate.class);
        when(flaky.delete(anyString())).thenThrow(new RuntimeException("del fail"));
        when(flaky.opsForSet()).thenAnswer(inv -> semi.opsForSet());   // 补偿队列走真内存
        ArticleCacheService svc = new ArticleCacheService(flaky, om);

        svc.evict(42L);
        assertThat(semi.opsForSet().members(ArticleCacheService.EVICT_RETRY)).contains("42");

        // 第二层：连补偿队列都进不去 → 彻底静默（Redis 全挂的世界线）
        ArticleCacheService dead = new ArticleCacheService(boomRedis(), om);
        assertThatCode(() -> dead.evict(42L)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("drainEvictRetry：读队列炸回0；单条补偿失败留队不阻断其余")
    void drainRetryQueueSemantics() {
        // 队列读取失败
        assertThat(new ArticleCacheService(boomRedis(), om).drainEvictRetry()).isZero();

        // 两条待补偿：42 正常、"脏值"解析炸留队。用真内存 Redis 布队列，
        // delete 通道套一层对 id=42 失败的 mock 太绕——改为验证"脏值成员不炸整轮"
        StringRedisTemplate mem = InMemoryRedis.template();
        ArticleCacheService svc = new ArticleCacheService(mem, om);
        mem.opsForSet().add(ArticleCacheService.EVICT_RETRY, "7", "脏值非数字");
        mem.opsForValue().set(ArticleCacheService.DETAIL_PREFIX + "7", "{\"id\":7}");

        long drained = svc.drainEvictRetry();
        // 正常成员被补偿并出队；脏成员解析炸留队（下轮再试）——本轮 drained≥1
        assertThat(drained).isGreaterThanOrEqualTo(1);
        assertThat(mem.opsForSet().members(ArticleCacheService.EVICT_RETRY)).contains("脏值非数字");
    }

    @Test
    @DisplayName("cacheStats 部分失败回已得键值（运维页宁可数字缺一块也不 500）")
    void cacheStatsDegradesToPartial() {
        // boom 形态：scanKeys 内部自带 try-catch 回空集（前两项以 0 入账），
        // 炸点在 opsForSet().size() → 结果含 detailKeys/listKeys 两个 0、缺后四项——
        // "部分成功部分缺席"正是该方法要的降级形状（不是空 Map 也不是异常）
        var boomStats = new ArticleCacheService(boomRedis(), om).cacheStats();
        assertThat(boomStats).containsEntry("detailKeys", 0L);
        assertThat(boomStats).doesNotContainKey("pvKeys");

        StringRedisTemplate mem = InMemoryRedis.template();
        ArticleCacheService svc = new ArticleCacheService(mem, om);
        mem.opsForValue().set(ArticleCacheService.DETAIL_PREFIX + "9", "{}");
        var stats = svc.cacheStats();
        assertThat(stats.get("detailKeys")).isEqualTo(1L);
        assertThat(stats).containsKeys("listKeys", "listIndexMembers", "pvKeys", "pvDayKeys", "evictRetryPending");
    }

    // ==================== 健康世界线的载荷契约 ====================

    @Test
    @DisplayName("详情缓存 JSON 回环 + 脏载荷按 miss 处理（缓存内容损坏不许把读路径炸 500）")
    void detailRoundTripAndCorruptPayload() {
        StringRedisTemplate mem = InMemoryRedis.template();
        ArticleCacheService svc = new ArticleCacheService(mem, om);

        svc.putDetail(1L, sampleVo());
        ArticleVO back = svc.getDetail(1L);
        assertThat(back).isNotNull();
        assertThat(back.getTitle()).isEqualTo("缓存载荷回环标题");

        // 手工塞坏 JSON：readValue 炸 → 折叠为 miss（与"未命中"同路回源）
        mem.opsForValue().set(ArticleCacheService.DETAIL_PREFIX + "2", "{坏掉的json");
        assertThat(svc.getDetail(2L)).isNull();
    }

    @Test
    @DisplayName("列表缓存 CachePage 回环：五字段无损；空页也要装得下（防穿透的前提）")
    void listPageRoundTrip() {
        StringRedisTemplate mem = InMemoryRedis.template();
        ArticleCacheService svc = new ArticleCacheService(mem, om);

        String key = svc.listKey("page=1&size=10&category=技术");
        // 同串必同键（规范化落键的确定性）；不同串不同键
        assertThat(key).isEqualTo(svc.listKey("page=1&size=10&category=技术"));
        assertThat(key).isNotEqualTo(svc.listKey("page=2&size=10&category=技术"));

        svc.putList(key, new ArticleCacheService.CachePage(0, 0, 1, 10, java.util.List.of()));
        ArticleCacheService.CachePage page = svc.getList(key);
        assertThat(page).isNotNull();
        assertThat(page.total()).isZero();
        assertThat(page.list()).isEmpty();

        // 脏载荷同样按 miss
        mem.opsForValue().set(key, "not-json");
        assertThat(svc.getList(key)).isNull();
    }

    @Test
    @DisplayName("evictTx 双删：立即删一次 + afterCommit 再删一次（D-5 竞态窗口的关门动作可数）")
    void evictTxRegistersAfterCommitSecondDelete() {
        StringRedisTemplate counting = mock(StringRedisTemplate.class);
        java.util.concurrent.atomic.AtomicInteger deletes = new java.util.concurrent.atomic.AtomicInteger();
        when(counting.delete(anyString())).thenAnswer(inv -> {
            deletes.incrementAndGet();
            return true;
        });
        // InMemoryRedis 的 opsForSet() 本身是 Mockito 打桩方法——在外部 when(...) 的
        // 任一侧调用它都会串桩（嵌套打桩=UnfinishedStubbing）。先在干净上下文里把
        // 目标 SetOperations 实例取成变量，再喂给外层打桩。
        StringRedisTemplate indexBackend = InMemoryRedis.template();
        org.springframework.data.redis.core.SetOperations<String, String> indexSets =
                indexBackend.opsForSet();
        when(counting.opsForSet()).thenReturn(indexSets);
        ArticleCacheService svc = new ArticleCacheService(counting, om);

        // 无事务上下文：只有第一删
        svc.evictTx(5L);
        assertThat(deletes.get()).isEqualTo(1);   // 只删详情键（list 索引空集不触发额外删）

        // 开同步上下文：注册回调 → afterCommit 触发第二删
        TransactionSynchronizationManager.initSynchronization();
        try {
            svc.evictTx(5L);
            int afterFirst = deletes.get();
            var syncs = TransactionSynchronizationManager.getSynchronizations();
            assertThat(syncs).isNotEmpty();
            for (TransactionSynchronization s : syncs) {
                s.afterCommit();
            }
            assertThat(deletes.get()).isGreaterThan(afterFirst);   // 第二删确实发生
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    @DisplayName("clearBlogCache 只扫两类展示键并计数；扫描炸回 0（一键清不炸运维口）")
    void clearBlogCacheCountsAndDegrades() {
        StringRedisTemplate mem = InMemoryRedis.template();
        ArticleCacheService svc = new ArticleCacheService(mem, om);
        mem.opsForValue().set(ArticleCacheService.DETAIL_PREFIX + "1", "{}");
        mem.opsForValue().set(ArticleCacheService.LIST_PREFIX + "abc", "[]");
        mem.opsForValue().set(ArticleCacheService.PV_PREFIX + "1", "3");   // PV 键不许被清

        long deleted = svc.clearBlogCache();
        assertThat(deleted).isGreaterThanOrEqualTo(2);
        // 边界钉子：PV 计数器在射程外（清一次=丢真实浏览量，M9 台账口径）
        assertThat(mem.opsForValue().get(ArticleCacheService.PV_PREFIX + "1")).isEqualTo("3");

        assertThat(new ArticleCacheService(boomRedis(), om).clearBlogCache()).isZero();
    }

    @Test
    @DisplayName("takeCounter：键消失回0 / 计数为负原样回（回写侧 delta<=0 跳过）/ 非数字值按0吞")
    void takeCounterEdges() {
        StringRedisTemplate mem = InMemoryRedis.template();
        ArticleCacheService svc = new ArticleCacheService(mem, om);

        assertThat(svc.takeCounter("不存在")).isZero();
        mem.opsForValue().set("blog:pv:8", "15");
        assertThat(svc.takeCounter("blog:pv:8")).isEqualTo(15);
        assertThat(mem.opsForValue().get("blog:pv:8")).isNull();   // 取走即归零（DEL 语义）

        mem.opsForValue().set("blog:pv:9", "非数字");
        assertThat(svc.takeCounter("blog:pv:9")).isZero();         // parse 炸按 0（键已删，脏值随键消失）
    }
}
