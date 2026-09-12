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
 * <p>关于"7 日 PV 趋势"（API 文档措辞）：浏览明细的唯一数据源是 Redis
 * blog:pv:{id} 计数器 + M9 定时回写 article.view_count，本期没有"按日 PV"的
 * 存储结构（view_count 是累计值不是时序）。与其造假一条线，本接口把 pvTrend
 * 回成全 0 并在 note 字段声明口径，M9 落地每日快照（或在回写任务里顺带写
 * pv:day:{yyyyMMdd} 计数键）后填充真实数据——前端图区读 note 展示，答辩可讲。
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
        List<DashboardVo.DayPoint> pvTrend = new ArrayList<>(TREND_DAYS);
        for (int i = TREND_DAYS - 1; i >= 0; i--) {
            pvTrend.add(DashboardVo.DayPoint.builder()
                    .date(LocalDate.now().minusDays(i).toString()).value(0L).build());
        }

        // 总浏览量：view_count 现值求和（M9 回写前它是存量基线，回写后自动生长）
        Long totalPv = 0L;
        List<Article> pvRows = articleMapper.selectList(new LambdaQueryWrapper<Article>()
                .select(Article::getViewCount));
        for (Article a : pvRows) {
            if (a.getViewCount() != null) {
                totalPv += a.getViewCount();
            }
        }
        counts.setAiCalls7d(ai7d);
        counts.setAiCost7d(cost7d.setScale(4, RoundingMode.HALF_UP));

        return DashboardVo.builder()
                .counts(counts)
                .aiTrend(aiTrend)
                .pvTrend(pvTrend)
                .totalPv(totalPv)
                .note("AI 趋势来自 ai_call_log 全口径（含缓存命中与失败）；"
                        + "PV 按日时序依赖 M9 浏览计数回写体系，当前为占位零序列。")
                .build();
    }
}
