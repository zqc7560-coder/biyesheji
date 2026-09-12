package com.blog.job;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.blog.module.article.entity.Article;
import com.blog.module.article.mapper.ArticleMapper;
import com.blog.module.article.service.ArticleCacheService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 浏览量回写任务（M9）——《系统设计.md》§6 决策 4 + §1 包规划里预留的 job/PvSyncJob。
 *
 * <p>为什么计数要先过 Redis 再定时回写而不是读接口直接 UPDATE：详情页是全站读
 * QPS 最高的接口，每读一行就 UPDATE 会把 MySQL 行锁变成读路径的串行点
 * （200 并发压测 M5 验收必挂），也让 article 表的页缓存反复失效。
 * INCR（读时）与批量加回写（5min 一次）把写 QPS 解耦掉，读接口只留两个微秒级 Redis 命令。
 *
 * <p>幂等与宕机取舍（项目计划 D-10）：
 * <ul>
 *   <li>回写语义是 {@code view_count = view_count + delta}（累加式），delta 来自
 *       "取走即归零"的 Redis 计数器——同一笔增量只会被取走一次，任务重入/双实例
 *       并发（毕设单实例，仅理论面）都只会造成"计数落在另一实例"，不会双计；</li>
 *   <li>GET→DEL 窗口的毫秒级新增量、DEL→DB 写之间宕机的已取增量，都按"尽力而为"
 *       丢弃——浏览量是统计语义不是账本语义，为它上事务性消息是过度设计；</li>
 *   <li>DB 写失败时把增量 INCR 回计数器（恢复现场），下轮重试——宁慢不丢。</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PvSyncJob {

    private final ArticleCacheService articleCache;
    private final ArticleMapper articleMapper;

    /**
     * 定时入口：fixedDelay=5min（上一轮结束后歇 5 分钟再跑，天然防重入——
     * cron 表达式在 JVM 卡顿时会攒出连发，fixedDelay 不会）。
     * initialDelay 可配置：生产 1 分钟后首轮；测试 profile 拨到 24h 禁用自动触发，
     * 由测试直接调用 {@link #syncNow()}（调度线程与断言赛跑是接口测试的经典假阳性源）。
     */
    @Scheduled(initialDelayString = "${blog.pv.initial-delay-ms:60000}",
               fixedDelayString = "${blog.pv.sync-interval-ms:300000}")
    public void scheduledSync() {
        try {
            Result r = syncNow();
            if (r.applied() > 0 || r.recovered() > 0) {
                log.info("PV 回写完成: 落库 {} 篇 (+{} 浏览), 补偿失效 {} 篇",
                        r.applied(), r.totalDelta(), r.recovered());
            }
        } catch (Exception e) {
            // 调度任务抛异常会终止该任务的后续执行（Spring 默认行为），必须整体兜住
            log.error("PV 回写任务异常（下轮重试）", e);
        }
    }

    /** 回写结果摘要（供手动触发/测试断言用） */
    public record Result(int applied, long totalDelta, long recovered) {
    }

    /**
     * 一轮完整回写：先排空失效补偿队列（顺带职责，见下），再把 blog:pv:* 增量加回 article 表。
     *
     * <p>补偿队列为什么搭这趟车：失效 DEL 失败的概率极低（Redis 抖动一瞬），单独
     * 为它开一个定时任务是给调度器添堵；PV 回写本身就是 5 分钟心跳，两个
     * "Redis↔MySQL 对账类"工作共用一个节拍，语义都是"最终一致的事后修复"。
     */
    public Result syncNow() {
        long recovered = articleCache.drainEvictRetry();
        // 顺带治理 pvday 键生命周期（10 天滚动 TTL，见 refreshDayPvTtl 注释）——
        // 与失效补偿同一节拍搭车：都是"Redis 侧的事后修复"，不值得各开任务
        articleCache.refreshDayPvTtl();

        int applied = 0;
        long totalDelta = 0;
        List<String> failures = new ArrayList<>();
        for (String key : articleCache.scanKeys(ArticleCacheService.PV_PREFIX + "*")) {
            long delta = articleCache.takeCounter(key);
            if (delta <= 0) {
                continue; // 空/负增量：键刚被并发取走或计数损坏，跳过即清理完成
            }
            try {
                Long articleId = Long.valueOf(key.substring(ArticleCacheService.PV_PREFIX.length()));
                // 累加式回写：不读旧值再写（读-改-写在并发下互相吞），SQL 表达式原子自增。
                // 文章已删除时 UPDATE 命中 0 行——增量随键一起消失，正确（没人再看得到的计数）。
                int rows = articleMapper.update(null, new LambdaUpdateWrapper<Article>()
                        .eq(Article::getId, articleId)
                        .setSql("view_count = view_count + " + delta));
                if (rows > 0) {
                    applied++;
                    totalDelta += delta;
                }
            } catch (NumberFormatException e) {
                log.warn("异常 PV 键（非数字后缀）跳过并删除: {}", key);
            } catch (Exception e) {
                // DB 故障：把增量放回计数器，下轮重试（宁慢不丢），键已删所以必须显式 INCR 回
                failures.add(key);
                log.warn("PV 回写失败，增量已放回计数器 key={} delta={}: {}", key, delta, e.getMessage());
                articleCache.restoreCounter(key, delta);
            }
        }
        return new Result(applied, totalDelta, recovered + failures.size());
    }
}
