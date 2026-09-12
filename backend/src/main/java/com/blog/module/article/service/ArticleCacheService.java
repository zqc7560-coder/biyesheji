package com.blog.module.article.service;

import com.blog.module.article.vo.ArticleVO;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

/**
 * 文章热点缓存（M9）——《系统设计.md》§3.2 时序图的 "Redis 缓存读写/失效" 两步落地。
 *
 * <p>键空间严格对齐《数据库设计.md》§4：
 * <ul>
 *   <li>{@code blog:article:{id}} String(JSON) 30min —— 文章详情缓存。
 *       <b>只缓存已发布文章的"公开视图"</b>：详情 VO 里含 version（乐观锁回显），
 *       但草稿/下线的可见性矩阵与作者身份相关（不同人看到的东西不同），
 *       混进缓存就是权限穿透；只存 status=1 的装配结果，任何人读到都一样，
 *       因此命中路径可以安全地跳过 assertReadable。</li>
 *   <li>{@code blog:list:{md5(查询参数)}} String(JSON) 5min —— 列表缓存。
 *       只缓存"公开列表"分支（非 mine/非 adminScope）：治理视图带用户身份维度，
 *       进缓存要么键爆炸要么越权，一律绕开。短 TTL 是 R-04 的兜底：
 *       就算某条失效路径漏了，5 分钟后自愈。</li>
 *   <li>{@code blog:listkeys} Set —— 列表键登记处。Redis 的 KEYS/SCAN 模式删除
 *       在生产大库上是阻塞/慢操作，设计文档因此约定"写入时登记、失效时按清单逐个 DEL"
 *       ——空间换时间，也是本类不出现任何 pattern 扫列表键的原因（清缓存运维口除外）。</li>
 * </ul>
 *
 * <p>两条贯穿全类的纪律：
 * <ol>
 *   <li><b>Cache-Aside 写后删（DEL）而非更新</b>（系统设计 §6 决策 3）：写路径只删不写，
 *       读路径 miss 才回填——"更新缓存"在并发读写下存在旧值覆盖新值的竞态，删除没有。</li>
 *   <li><b>缓存是加速器不是正确性依赖</b>：所有 Redis 操作 try-catch，读失败按未命中
 *       走 DB，删失败进 {@code blog:evict:retry} 集合由定时任务补偿（R-04 应急路径），
 *       绝不让缓存故障把正常请求炸成 500。</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ArticleCacheService {

    /** 详情 TTL 30min、列表 TTL 5min（《数据库设计.md》§4 表列的约定值，不做成配置项：
     *  调这两个数属于"改设计"而不是"改运行参数"，进 yml 反而给了随手改坏一致性的口子 */
    private static final Duration DETAIL_TTL = Duration.ofMinutes(30);
    private static final Duration LIST_TTL = Duration.ofMinutes(5);

    public static final String DETAIL_PREFIX = "blog:article:";
    public static final String LIST_PREFIX = "blog:list:";
    public static final String LIST_INDEX = "blog:listkeys";
    /** 删除失败的补偿队列（R-04："DEL 失败进重试集合定时补偿"） */
    public static final String EVICT_RETRY = "blog:evict:retry";
    /** 浏览计数器（《数据库设计.md》§4 blog:pv:{id}，无 TTL——由定时任务取走归零） */
    public static final String PV_PREFIX = "blog:pv:";
    /** 按日 PV 计数键（§4 扩展行，文档已同步）：仪表盘 pvTrend 的真实数据源；
     *  与 blog:pv:{id} 分前缀是刻意的——回写任务对 blog:pv:* 做模式扫描，
     *  若日键也挂在该前缀下，"day"会被当成文章 ID 解析炸掉 */
    public static final String PVDAY_PREFIX = "blog:pvday:";

    private final StringRedisTemplate redis;
    /** 复用应用级 ObjectMapper：与 HTTP 响应同一套序列化配置（NON_NULL + JavaTimeModule），
     *  保证"进缓存的 JSON"与"给前端的 JSON"字段形状严格一致，避免两条路径形状漂移 */
    private final ObjectMapper objectMapper;

    // ==================================================================
    // 详情
    // ==================================================================

    /**
     * 读详情缓存。返回 null 即"未命中或不可用"——异常与未命中折叠为同一结果，
     * 调用方统一走回源路径，不需要区分（区分了也只是多写一个 catch 分支）。
     */
    public ArticleVO getDetail(Long id) {
        try {
            String json = redis.opsForValue().get(DETAIL_PREFIX + id);
            if (json == null || json.isBlank()) {
                return null;
            }
            return objectMapper.readValue(json, ArticleVO.class);
        } catch (Exception e) {
            log.warn("详情缓存读取失败（按未命中处理）id={}: {}", id, e.getMessage());
            return null;
        }
    }

    /** 回填详情缓存（仅发布态文章调用，见类注释的权限论证） */
    public void putDetail(Long id, ArticleVO vo) {
        try {
            redis.opsForValue().set(DETAIL_PREFIX + id,
                    objectMapper.writeValueAsString(vo), DETAIL_TTL);
        } catch (Exception e) {
            log.warn("详情缓存写入失败 id={}: {}", id, e.getMessage());
        }
    }

    // ==================================================================
    // 列表（CachePage 是缓存专用的内部信封：不直接序列化 PageResult 是刻意的——
    // common 层的 PageResult 无空参构造，为缓存给公共防腐类加 Jackson 注解属于
    // "让基础设施反向污染契约层"，宁可在这里包一层 record）
    // ==================================================================

    /** 缓存载荷：与 PageResult 同构的五字段 record（Jackson 对 record 走规范构造器，无需默认构造） */
    public record CachePage(long total, long pages, long pageNum, long pageSize, List<ArticleVO> list) {
    }

    /**
     * 列表缓存键：blog:list:{md5(规范化参数串)}。
     * 规范化的意义：同一查询的两种书写（keyword 前后空格、缺省与显式 null）必须落到同一键，
     * 否则"看似不同实则相同"的键会各占一份内存、各持一份旧值。
     */
    public String listKey(String canonical) {
        return LIST_PREFIX + md5Hex(canonical);
    }

    public CachePage getList(String key) {
        try {
            String json = redis.opsForValue().get(key);
            if (json == null || json.isBlank()) {
                return null;
            }
            return objectMapper.readValue(json, CachePage.class);
        } catch (Exception e) {
            log.warn("列表缓存读取失败（按未命中处理）key={}: {}", key, e.getMessage());
            return null;
        }
    }

    /** 回填列表缓存并登记进 blog:listkeys（失效时按登记清单逐个删，不用 KEYS 扫描） */
    public void putList(String key, CachePage page) {
        try {
            redis.opsForValue().set(key, objectMapper.writeValueAsString(page), LIST_TTL);
            redis.opsForSet().add(LIST_INDEX, key);
        } catch (Exception e) {
            log.warn("列表缓存写入失败 key={}: {}", key, e.getMessage());
        }
    }

    // ==================================================================
    // 失效
    // ==================================================================

    /**
     * 失效一篇文章的全部可见性缓存：详情键 + 所有登记过的列表键。
     *
     * <p>列表键不做"精准失效"（只删包含该文章的列表）：列表的命中条件是参数组合，
     * 反查"哪些列表里会出现这篇文章"需要解析每个键的参数语义，复杂度不成比例；
     * 博客读多写少（写 QPS ≈ 0），全删列表的代价是一次冷启动回源，设计文档
     * （§6 决策 3）正是据此选择了"通配删除（键登记集合）"方案。
     */
    public void evict(Long articleId) {
        try {
            redis.delete(DETAIL_PREFIX + articleId);
            Set<String> listKeys = redis.opsForSet().members(LIST_INDEX);
            if (listKeys != null) {
                for (String k : listKeys) {
                    redis.delete(k);
                }
                if (!listKeys.isEmpty()) {
                    redis.delete(LIST_INDEX);
                }
            }
        } catch (Exception e) {
            // DEL 失败进重试集合：下一轮定时补偿再删（R-04 应急路径）。
            // 即便补偿也失败，短 TTL 仍是最后防线——三层防线按可靠性递减排列。
            log.warn("缓存失效失败，转入补偿队列 id={}: {}", articleId, e.getMessage());
            try {
                redis.opsForSet().add(EVICT_RETRY, String.valueOf(articleId));
            } catch (Exception ignore) {
                // Redis 整体不可知时无处可存，只能靠 TTL 自愈，不再降级记录（避免日志风暴）
            }
        }
    }

    /**
     * 事务安全的失效（写路径统一入口）——"双删"结构：
     * <ol>
     *   <li>立即 DEL：事务内的读（以及本类测试里"改完立刻查"的场景）能拿到回源新值；</li>
     *   <li>afterCommit 再 DEL：堵住"事务提交前另一请求回源、把未提交前的旧值写回缓存"
     *       的经典竞态窗口（D-5）。提交回滚时不触发第二次 DEL——数据没变，无需失效。</li>
     * </ol>
     * 无事务上下文（如测试外的直接调用）时仅第一次 DEL 生效，语义不劣化。
     */
    public void evictTx(Long articleId) {
        evict(articleId);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    evict(articleId);
                }
            });
        }
    }

    /**
     * 排空补偿队列（PvSyncJob 每轮顺带执行）：逐个重试失效，成功即出队。
     *
     * @return 本轮成功补偿的条数
     */
    public long drainEvictRetry() {
        long drained = 0;
        try {
            Set<String> pending = redis.opsForSet().members(EVICT_RETRY);
            if (pending == null || pending.isEmpty()) {
                return 0;
            }
            for (String idStr : pending) {
                try {
                    evict(Long.parseLong(idStr));
                    redis.opsForSet().remove(EVICT_RETRY, idStr);
                    drained++;
                } catch (Exception e) {
                    // 单条失败留队等下轮，不阻断其余补偿
                    log.warn("补偿失效失败 id={}: {}", idStr, e.getMessage());
                }
            }
        } catch (Exception e) {
            log.warn("补偿队列读取失败（下轮再试）: {}", e.getMessage());
        }
        return drained;
    }

    // ==================================================================
    // 浏览量计数（系统设计 §6 决策 4：读接口 INCR Redis + 定时回写，读写解耦）
    // ==================================================================

    /**
     * 浏览计一次：异步路径调用（Controller 已把整个动作甩到 pvExecutor），
     * 每个访问多两个 Redis 命令换掉"每次读文章 UPDATE 主表"的行锁热点——
     * 读 QPS 与写 QPS 解耦是详情页撑住 200 并发（M5 性能验收）的前提。
     *
     * <p>两个键同增：blog:pv:{id}（待回写进 article.view_count 的存量增量）
     * 与 blog:pvday:{yyyyMMdd}（按日时序，仪表盘 pvTrend 数据源）。
     * 无 TTL、失败静默：计数是尽力而为的统计语义，丢几笔浏览不影响正确性，
     * 为它冒同步阻塞或异常传播的险是本末倒置。
     */
    public void countPv(Long articleId) {
        try {
            redis.opsForValue().increment(PV_PREFIX + articleId);
            redis.opsForValue().increment(PVDAY_PREFIX
                    + java.time.LocalDate.now()
                          .format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE));
        } catch (Exception e) {
            log.warn("PV 计数失败（丢一笔无妨）id={}: {}", articleId, e.getMessage());
        }
    }

    /**
     * 原子取走某键的当前计数并归零（GETDEL 语义的兼容实现）。
     *
     * <p>为什么 get+delete 而不是 INCRBY -delta 或 Lua：单实例下 GET→DEL 的窗口里
     * 新到达的增量最坏丢一笔（微秒窗口 × 个位数 QPS），换来的是零额外依赖；
     * Lua 原子版留作演进项，毕设口径下诚实的近似比花哨的实现更值得答辩讲。
     * 键不存在返回 0——扫描到键与读值之间的窗口里键恰好被回写删掉是合法竞态。
     */
    public long takeCounter(String key) {
        try {
            String v = redis.opsForValue().get(key);
            if (v == null) {
                return 0;
            }
            redis.delete(key);
            return Long.parseLong(v);
        } catch (Exception e) {
            log.warn("计数器取走失败 key={}: {}", key, e.getMessage());
            return 0;
        }
    }

    /** 按模式列键（供回写任务扫 blog:pv:*；键量级小，KEYS 可接受，理由同 clearBlogCache） */
    public Set<String> scanKeys(String pattern) {
        try {
            Set<String> keys = redis.keys(pattern);
            return keys == null ? Set.of() : keys;
        } catch (Exception e) {
            log.warn("键扫描失败 pattern={}: {}", pattern, e.getMessage());
            return Set.of();
        }
    }

    /** 读一个计数器当前值（不回写口径展示用：view_count 现值 + blog:pv:{id} 未落账增量） */
    public long peekCounter(String key) {
        try {
            String v = redis.opsForValue().get(key);
            return v == null ? 0 : Long.parseLong(v);
        } catch (Exception e) {
            return 0;
        }
    }

    /** 读按日 PV 键（不存在回 0；异常回 0——统计数字的缺席好过整页 500） */
    public long peekDayPv(String yyyyMMdd) {
        return peekCounter(PVDAY_PREFIX + yyyyMMdd);
    }

    /** 把已取走的增量放回计数器（DB 回写失败时的"宁慢不丢"恢复现场；INCR 语义天然可加） */
    public void restoreCounter(String key, long delta) {
        try {
            redis.opsForValue().increment(key, delta);
        } catch (Exception e) {
            log.warn("计数恢复失败（这 {} 笔浏览认赔）key={}: {}", delta, key, e.getMessage());
        }
    }

    /**
     * 给按日 PV 键续 10 天 TTL（回写任务每轮顺带执行）。
     * pvday 键不在 blog:pv:* 扫描范围内（前缀刻意分开），由本方法独立治理生命周期：
     * 仪表盘窗口只要 7 天，10 天给跨日边界留余量；一直活跃就无限续期，
     * 停止访问 10 天后自动消失——不给 Redis 攒永久垃圾键。
     */
    public void refreshDayPvTtl() {
        try {
            for (String k : scanKeys(PVDAY_PREFIX + "*")) {
                redis.expire(k, Duration.ofDays(10));
            }
        } catch (Exception e) {
            log.warn("pvday TTL 续期失败（键暂成无 TTL，量小无害）: {}", e.getMessage());
        }
    }

    // ==================================================================
    // 运维：一键清缓存（R-04 应急口，GET 统计 + DELETE 清空由 CacheOpsService 组合）
    // ==================================================================

    /**
     * 全量失效（分类改名/标签合并等"波及面不可枚举"的治理动作专用）。
     *
     * <p>为什么不精准失效：分类"技术"→"科技"会出现在任意列表筛选页与任意挂载
     * 文章的详情缓存里，反查受影响键需要解析列表键的参数语义（键是 md5，不可逆）
     * 外加全扫详情键比对 categoryId——复杂度不成比例。治理动作是低频 ADMIN 操作，
     * 一次全删的代价是所有页一次冷启动回源，读多写少场景下完全可接受。
     */
    public long evictAll() {
        long deleted = 0;
        try {
            for (String k : scanKeys(DETAIL_PREFIX + "*")) {
                redis.delete(k);
                deleted++;
            }
            Set<String> listKeys = redis.opsForSet().members(LIST_INDEX);
            if (listKeys != null) {
                for (String k : listKeys) {
                    redis.delete(k);
                    deleted++;
                }
                redis.delete(LIST_INDEX);
            }
        } catch (Exception e) {
            log.warn("全量失效失败（短 TTL 兜底自愈）: {}", e.getMessage());
        }
        return deleted;
    }

    /**
     * 缓存体系健康度快照（运维口 GET /api/admin/cache/stats 的数据源）。
     * 各前缀键数 + 簿记集合基数；Redis 故障回空 Map（可观测性数据不许把管理页炸成 500）。
     */
    public java.util.Map<String, Long> cacheStats() {
        java.util.Map<String, Long> out = new java.util.LinkedHashMap<>();
        try {
            out.put("detailKeys", (long) scanKeys(DETAIL_PREFIX + "*").size());
            out.put("listKeys", (long) scanKeys(LIST_PREFIX + "*").size());
            Long idxSize = redis.opsForSet().size(LIST_INDEX);
            out.put("listIndexMembers", idxSize == null ? 0L : idxSize);
            out.put("pvKeys", (long) scanKeys(PV_PREFIX + "*").size());
            out.put("pvDayKeys", (long) scanKeys(PVDAY_PREFIX + "*").size());
            Long retrySize = redis.opsForSet().size(EVICT_RETRY);
            out.put("evictRetryPending", retrySize == null ? 0L : retrySize);
        } catch (Exception e) {
            log.warn("缓存统计读取失败（Redis 不可用）: {}", e.getMessage());
        }
        return out;
    }

    /**
     * 清全部 blog:* 缓存类键并返回删除数。刻意不用 KEYS 的生产替代 SCAN：
     * 毕设量级（键数万以下）KEYS 毫秒返回，代码可读性优先；
     * 生产万级以上键量时此口应改 SCAN 游标——已在注释声明，属演进项。
     *
     * <p>只清"缓存类"键：pv 计数器（未回写浏览量）与 ai:* 结果缓存（清一次=多花一次钱）
     * 与 token:blacklist（安全键）都不在射程内，边界由 CacheOpsService 统一注释。
     */
    public long clearBlogCache() {
        long deleted = 0;
        try {
            for (String pattern : List.of(DETAIL_PREFIX + "*", LIST_PREFIX + "*")) {
                Set<String> keys = redis.keys(pattern);
                if (keys != null) {
                    for (String k : keys) {
                        redis.delete(k);
                        deleted++;
                    }
                }
            }
            // listkeys 索引整体作废（成员已随上一步删除，残留索引只会让下次失效白删）
            if (Boolean.TRUE.equals(redis.delete(LIST_INDEX))) {
                // 索引键本身不计入 deleted 统计口径：它是簿记不是数据
            }
        } catch (Exception e) {
            log.warn("清缓存失败: {}", e.getMessage());
        }
        return deleted;
    }

    /** MD5 hex：仅用于缓存键散列（非安全场景），SHA-256 的 64 字符对键名纯属浪费 */
    private static String md5Hex(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            return HexFormat.of().formatHex(md.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            // JDK 规范要求 MD5 必须可用；真进不来是 JVM 坏了
            throw new IllegalStateException("MD5 不可用", e);
        }
    }
}
