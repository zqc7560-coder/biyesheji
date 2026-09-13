package com.blog.job;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.blog.module.article.entity.Article;
import com.blog.module.article.mapper.ArticleMapper;
import com.blog.module.article.service.ArticleCacheService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.apache.ibatis.builder.MapperBuilderAssistant;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * M11 测试完善 · PV 回写任务纯单元（mock 缓存服务 + mock Mapper）。
 *
 * <p>接口级路径（CacheApiM9Test）已经把"真 Redis 计数器 → 真 H2 表"的主干走通了，
 * 但回写任务的**异常分支**恰是它的存在意义——M9 设计注记"宁慢不丢"全部分支在
 * happy-path 接口测试里到不了：DB 写失败恢复现场、脏键跳过、空增量让位。
 * mock 可以把每个分支单独扳机，这是接口测试做不到的（要模拟"UPDATE 恰好炸"
 * 得污染共享库或注入故障开关，得不偿失）。
 *
 * <p>钉死的分支矩阵（{@link PvSyncJob#syncNow()} 逐行对应）：
 * ① delta>0 且 UPDATE 命中 → applied/totalDelta 累计；
 * ② delta≤0（被并发取走）→ 跳过不写库；
 * ③ 非数字后缀脏键 → 不抛、不回写；
 * ④ UPDATE 抛异常 → restoreCounter 放回增量（宁慢不丢），recovered 计数；
 * ⑤ 每轮顺带 drainEvictRetry + refreshDayPvTtl（补偿搭车契约）；
 * ⑥ scheduledSync 整体兜异常（调度任务抛异常会杀死后续排程——Spring 默认行为）。
 */
@DisplayName("M11 PV回写: 命中/空量/脏键/故障恢复/调度兜底")
class PvSyncJobM11Test {

    private ArticleCacheService cache;
    private ArticleMapper articleMapper;
    private PvSyncJob job;

    @BeforeEach
    void setUp() {
        cache = mock(ArticleCacheService.class);
        articleMapper = mock(ArticleMapper.class);
        job = new PvSyncJob(cache, articleMapper);
        // syncNow 内部 new LambdaUpdateWrapper(Article) 依赖 MP TableInfo 缓存；
        // 纯 mock 环境无容器装配，手工预热（与 AiCallLogServiceM11Test 同一手法）
        if (TableInfoHelper.getTableInfo(Article.class) == null) {
            TableInfoHelper.initTableInfo(
                    new MapperBuilderAssistant(new MybatisConfiguration(), ""), Article.class);
        }
    }

    /** 保序 Set：scanKeys 返回 Set，多键用例的遍历顺序影响链式 stub 断言，用 LinkedHashSet 钉死 */
    private static Set<String> setOf(String... keys) {
        return new LinkedHashSet<>(List.of(keys));
    }

    private static String pvKey(long id) {
        return ArticleCacheService.PV_PREFIX + id;
    }

    @Test
    @DisplayName("正常增量：两键分别回写，applied/totalDelta 精确；每轮搭车排空补偿+刷TTL")
    void normalWriteBack() {
        when(cache.scanKeys(anyString())).thenReturn(setOf(pvKey(1L), pvKey(2L)));
        when(cache.takeCounter(pvKey(1L))).thenReturn(7L);
        when(cache.takeCounter(pvKey(2L))).thenReturn(3L);
        when(articleMapper.update(any(), any(Wrapper.class))).thenReturn(1);
        when(cache.drainEvictRetry()).thenReturn(2L);

        PvSyncJob.Result r = job.syncNow();

        assertThat(r.applied()).isEqualTo(2);
        assertThat(r.totalDelta()).isEqualTo(10);
        assertThat(r.recovered()).isEqualTo(2);          // 补偿排空数进入 recovered
        verify(cache).drainEvictRetry();
        verify(cache).refreshDayPvTtl();
        verify(articleMapper, org.mockito.Mockito.times(2)).update(any(), any(Wrapper.class));
    }

    @Test
    @DisplayName("空/负增量让位（键刚被并发取走）：不触库、不计 applied")
    void nonPositiveDeltaSkipped() {
        when(cache.scanKeys(anyString())).thenReturn(setOf(pvKey(9L)));
        when(cache.takeCounter(pvKey(9L))).thenReturn(0L);

        PvSyncJob.Result r = job.syncNow();

        assertThat(r.applied()).isZero();
        verify(articleMapper, never()).update(any(), any(Wrapper.class));
    }

    @Test
    @DisplayName("脏键（非数字后缀，如误写的 blog:pv:abc）：警告跳过，绝不拖崩整轮回写")
    void dirtyKeySkippedNotFatal() {
        when(cache.scanKeys(anyString())).thenReturn(setOf(ArticleCacheService.PV_PREFIX + "abc", pvKey(5L)));
        when(cache.takeCounter(ArticleCacheService.PV_PREFIX + "abc")).thenReturn(4L);
        when(cache.takeCounter(pvKey(5L))).thenReturn(6L);
        when(articleMapper.update(any(), any(Wrapper.class))).thenReturn(1);

        PvSyncJob.Result r = job.syncNow();

        // 脏键没写库但后面的正常键必须继续处理（for 循环内 catch 单键隔离）
        assertThat(r.applied()).isEqualTo(1);
        assertThat(r.totalDelta()).isEqualTo(6);
    }

    @Test
    @DisplayName("DB 写失败：增量 restoreCounter 放回现场（宁慢不丢），计入 recovered，循环继续")
    void dbFailureRestoresCounter() {
        when(cache.scanKeys(anyString())).thenReturn(setOf(pvKey(1L), pvKey(2L)));
        when(cache.takeCounter(pvKey(1L))).thenReturn(4L);
        when(cache.takeCounter(pvKey(2L))).thenReturn(5L);
        when(articleMapper.update(any(), any(Wrapper.class)))
                .thenThrow(new RuntimeException("deadlock"))
                .thenReturn(1);

        PvSyncJob.Result r = job.syncNow();

        verify(cache).restoreCounter(pvKey(1L), 4L);   // 第一键故障→现场恢复
        assertThat(r.applied()).isEqualTo(1);          // 第二键照常落库
        assertThat(r.totalDelta()).isEqualTo(5);
        assertThat(r.recovered()).isEqualTo(1);        // 失败键计入 recovered（补偿语义）
    }

    @Test
    @DisplayName("UPDATE 命中 0 行（文章已删）：不计 applied——增量随键消失是正确结局")
    void zeroRowsNotApplied() {
        when(cache.scanKeys(anyString())).thenReturn(setOf(pvKey(66L)));
        when(cache.takeCounter(pvKey(66L))).thenReturn(9L);
        when(articleMapper.update(any(), any(Wrapper.class))).thenReturn(0);

        PvSyncJob.Result r = job.syncNow();

        assertThat(r.applied()).isZero();
        assertThat(r.totalDelta()).isZero();
    }

    @Test
    @DisplayName("scheduledSync 兜住一切异常：调度任务抛出=该任务永久停摆（Spring 语义），绝不允许")
    void scheduledWrapperSwallows() {
        when(cache.scanKeys(anyString())).thenThrow(new RuntimeException("redis down"));
        assertThatCode(() -> job.scheduledSync()).doesNotThrowAnyException();

        // 正常轮次的日志分支也要走到（applied>0 才打统计行）
        org.mockito.Mockito.reset(cache, articleMapper);
        when(cache.scanKeys(anyString())).thenReturn(setOf(pvKey(3L)));
        when(cache.takeCounter(anyString())).thenReturn(1L);
        when(articleMapper.update(any(), any(Wrapper.class))).thenReturn(1);
        assertThatCode(() -> job.scheduledSync()).doesNotThrowAnyException();
    }
}
