package com.blog.module.system.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.blog.module.ai.entity.AiCallLog;
import com.blog.module.ai.mapper.AiCallLogMapper;
import com.blog.module.article.entity.Article;
import com.blog.module.article.mapper.ArticleMapper;
import com.blog.module.comment.entity.Comment;
import com.blog.module.comment.mapper.CommentMapper;
import com.blog.module.system.vo.DashboardVo;
import com.blog.module.user.entity.SysUser;
import com.blog.module.user.mapper.SysUserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 仪表盘聚合服务（M8）—— GET /api/admin/dashboard（《API接口设计.md》§8）。
 *
 * <p>实现取向：全部计数走 MyBatis-Plus selectCount（各表现有索引够用，万级行
 * 毫秒返回），近 7 日趋势走"范围查 + Java 侧按日分桶"。不在 SQL 里做
 * DATE(created_at) 分组是刻意的——M7 已验证的教训：日期分组函数在 H2/MySQL
 * 的返回类型与格式存在方言漂移（DATETIME vs DATE、有无时分秒），Java 端
 * getModifiedDate 分桶两库行为恒等，测试即真值。
 *
 * <p>关于"7 日 PV 趋势"：M9 落地后数据源是 Redis blog:pvday:{yyyyMMdd} 按日计数键
 * （详情页每次浏览 INCR，见 ArticleCacheService#countPv）。读侧直接 peek 当日键现值，
 * <b>不做"取走归零"</b>——仪表盘是只读消费者，归零职责在 PvSyncJob 的 TTL 续期，
 * 两边都要动同一个键就会打架。pvday 键 10 天 TTL（refreshDayPvTtl 治理）天然覆盖
 * 7 日窗口；Redis 不可用时全部回 0 ——与 M8 占位行为一致，页面 note 口径统一说明。
 * totalPv 的口径升级：view_count 存量 + 尚未回写的 blog:pv:* 增量 = 用户看到的
 * "实时总浏览"，否则前台详情页 PV 已涨而看板总数不动，两处对不上会被当 bug。
 */
@Service
@RequiredArgsConstructor
public class DashboardService {

    /** 趋势窗口天数（文档口径"7 日"） */
    private static final int TREND_DAYS = 7;

    private final ArticleMapper articleMapper;
    private final SysUserMapper userMapper;
    private final CommentMapper commentMapper;
    private final AiCallLogMapper aiCallLogMapper;
    /** M9：PV 趋势/实时增量的 Redis 读口（叶子组件，无环；异常在方法内各自吞，统计缺数好过 500） */
    private final com.blog.module.article.service.ArticleCacheService articleCache;

    /** 聚合快照（每请求现算，ADMIN 低频访问不加缓存；若后台页被挂轮询再随 M9 缓存化） */
    public DashboardVo snapshot() {
        LocalDateTime trendFrom = LocalDate.now().minusDays(TREND_DAYS - 1L).atStartOfDay();

        // ---------- 计数块：全部用 selectCount（MP 自动拼 deleted=0，逻辑删行不掺和） ----------
        DashboardVo.Counts counts = DashboardVo.Counts.builder()
                .articleTotal(articleMapper.selectCount(null))
                .articlePublished(articleMapper.selectCount(
                        new LambdaQueryWrapper<Article>().eq(Article::getStatus, 1)))
                .userTotal(userMapper.selectCount(null))
                .userActive(userMapper.selectCount(
                        new LambdaQueryWrapper<SysUser>().eq(SysUser::getStatus, 1)))
                .commentTotal(commentMapper.selectCount(null))
                // 待审数是治理页红点的同一口径（status=0），两处数字对不上会被当 bug 提
                .commentPending(commentMapper.selectCount(
                        new LambdaQueryWrapper<Comment>().eq(Comment::getStatus, 0)))
                .build();

        // ---------- AI 趋势：一次范围查（近 7 日全量日志行），Java 分桶 ----------
        List<AiCallLog> logs = aiCallLogMapper.selectList(new LambdaQueryWrapper<AiCallLog>()
                .select(AiCallLog::getCreatedAt, AiCallLog::getCostEst)
                .ge(AiCallLog::getCreatedAt, trendFrom));
        Map<LocalDate, long[]> byDay = new TreeMap<>();   // 日 → [次数, 成本(微元累加避 float)]
        long ai7d = 0;
        BigDecimal cost7d = BigDecimal.ZERO;
        for (AiCallLog logRow : logs) {
            LocalDate d = logRow.getCreatedAt().toLocalDate();
            byDay.computeIfAbsent(d, k -> new long[2])[0]++;
            ai7d++;
            if (logRow.getCostEst() != null) {
                cost7d = cost7d.add(logRow.getCostEst());
                byDay.get(d)[1] += logRow.getCostEst()
                        .multiply(BigDecimal.valueOf(1_000_000)).longValue();
            }
        }

        // 补零：折线图缺日会断线，日历日序列必须稠密（今天恒在末位）
        List<DashboardVo.DayPoint> aiTrend = new ArrayList<>(TREND_DAYS);
        for (int i = TREND_DAYS - 1; i >= 0; i--) {
            LocalDate d = LocalDate.now().minusDays(i);
            long[] agg = byDay.get(d);
            aiTrend.add(DashboardVo.DayPoint.builder()
                    .date(d.toString()).value(agg == null ? 0L : agg[0]).build());
        }
        // PV 趋势（M9 真实化）：逐日 peek blog:pvday:{yyyyMMdd}。
        // 日期格式必须与 countPv 写入侧同为 BASIC_ISO_DATE（yyyyMMdd 无横杠）——
        // 两侧格式漂移就是"永远查不到键"的静默零序列，所以这里显式用同一个常量类。
        java.time.format.DateTimeFormatter dayFmt = java.time.format.DateTimeFormatter.BASIC_ISO_DATE;
        List<DashboardVo.DayPoint> pvTrend = new ArrayList<>(TREND_DAYS);
        for (int i = TREND_DAYS - 1; i >= 0; i--) {
            LocalDate d = LocalDate.now().minusDays(i);
            pvTrend.add(DashboardVo.DayPoint.builder()
                    .date(d.toString())
                    .value(articleCache.peekDayPv(d.format(dayFmt)))
                    .build());
        }

        // 总浏览量：view_count 存量（已回写） + blog:pv:* 未回写增量（M9 实时口径）。
        // 增量部分逐键 peek（键量级=5 分钟内被浏览的文章数，个位到十位，无压力）。
        Long totalPv = 0L;
        List<Article> pvRows = articleMapper.selectList(new LambdaQueryWrapper<Article>()
                .select(Article::getViewCount));
        for (Article a : pvRows) {
            if (a.getViewCount() != null) {
                totalPv += a.getViewCount();
            }
        }
        for (String k : articleCache.scanKeys(
                com.blog.module.article.service.ArticleCacheService.PV_PREFIX + "*")) {
            totalPv += articleCache.peekCounter(k);
        }
        counts.setAiCalls7d(ai7d);
        counts.setAiCost7d(cost7d.setScale(4, RoundingMode.HALF_UP));

        return DashboardVo.builder()
                .counts(counts)
                .aiTrend(aiTrend)
                .pvTrend(pvTrend)
                .totalPv(totalPv)
                .note("AI 趋势来自 ai_call_log 全口径（含缓存命中与失败）；"
                        + "PV 按日时序来自 Redis blog:pvday 计数键（实时未回写），"
                        + "累计浏览=view_count 存量+未回写增量；Redis 不可用时 PV 两项回 0。")
                .build();
    }
}
