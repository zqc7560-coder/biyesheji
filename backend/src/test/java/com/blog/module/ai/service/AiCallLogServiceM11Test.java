package com.blog.module.ai.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.blog.module.ai.entity.AiCallLog;
import com.blog.module.ai.enums.BizType;
import com.blog.module.ai.mapper.AiCallLogMapper;
import com.blog.module.ai.vo.AiStatsVo;
import com.blog.module.system.entity.SysConfig;
import com.blog.module.user.entity.SysUser;
import com.blog.module.user.mapper.SysUserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * M11 测试完善 · AI 记账服务纯单元（Mockito 直 mock Mapper，不起 Spring 容器）。
 *
 * <p>为什么这层用 mock 而不是接口级测试再盖：AiCallLogService 的分支全部是
 * "数据形态判定"（status 映射、成本算式、脱敏、截断、聚合），不碰 SQL 语义——
 * mock Mapper + ArgumentCaptor 能把**落库行的每个字段**钉成断言，比接口测试
 * "插进去再查出来看个大概"精确一个数量级，且毫秒级完成。真实 SQL 路径已由
 * AiApiM6/M7Test、CacheApiM9Test 等在 H2 上贯通，两层互补。
 *
 * <p>钉的核心契约（《AI模块设计.md》§3.12/§6.3，答辩统计数据的口径源头）：
 * ① status 语义：1 直成 / 2 失败 / 3 重试后成功；② 缓存命中恒 cost=0；
 * ③ error_msg 脱敏（sk- 前缀回声清洗）+ 500 截断；④ 记账写失败绝不影响业务
 * （insertQuietly 吞异常）；⑤ stats 聚合的 Java 侧算术（TC-AI-10 成本一致性）。
 */
@DisplayName("M11 AI记账: status映射/成本估算/脱敏截断/失败隔离/stats聚合")
class AiCallLogServiceM11Test {

    private AiCallLogMapper logMapper;
    private SysUserMapper userMapper;
    private AiSysConfigReader configReader;
    private AiCallLogService service;

    @BeforeEach
    void setUp() {
        logMapper = mock(AiCallLogMapper.class);
        userMapper = mock(SysUserMapper.class);
        configReader = mock(AiSysConfigReader.class);
        service = new AiCallLogService(logMapper, userMapper, configReader);
        // stats() 内部用 LambdaQueryWrapper 按方法引用定位列——这依赖 MyBatis-Plus 的
        // TableInfo 全局缓存。正常由 Spring 容器启动时装配填充；本类刻意不起容器
        // （纯 mock 快测），所以手工预热实体元数据缓存（M1 台账的 TableInfoHelper 用法）。
        if (TableInfoHelper.getTableInfo(AiCallLog.class) == null) {
            MybatisConfiguration conf = new MybatisConfiguration();
            conf.setUseGeneratedShortKey(false);
            TableInfoHelper.initTableInfo(
                    new org.apache.ibatis.builder.MapperBuilderAssistant(conf, ""), AiCallLog.class);
        }
    }

    /** 捕获唯一一次 insert 的行对象 */
    private AiCallLog capturedRow() {
        ArgumentCaptor<AiCallLog> cap = ArgumentCaptor.forClass(AiCallLog.class);
        verify(logMapper).insert(cap.capture());
        return cap.getValue();
    }

    // ==================== status 映射与成本 ====================

    @Test
    @DisplayName("直接成功=1、重试后成功=3、缓存命中仍=1——retryCount 与 cacheHit 双维正交")
    void successStatusMapping() {
        when(configReader.pricePer1kPrompt()).thenReturn(BigDecimal.ZERO);
        when(configReader.pricePer1kCompletion()).thenReturn(BigDecimal.ZERO);

        service.recordSuccess(1L, BizType.SUMMARY, 9L, "m", 10, 20, 100, 0, false, "h");
        assertThat(capturedRow().getStatus()).isEqualTo(1);

        Mockito.reset(logMapper);
        service.recordSuccess(1L, BizType.SUMMARY, 9L, "m", 10, 20, 800, 2, false, "h");
        AiCallLog retried = capturedRow();
        assertThat(retried.getStatus()).isEqualTo(3);
        assertThat(retried.getRetryCount()).isEqualTo(2);

        Mockito.reset(logMapper);
        service.recordSuccess(1L, BizType.SUMMARY, 9L, "m", null, null, 2, 0, true, "h");
        assertThat(capturedRow().getStatus()).isEqualTo(1);
    }

    @Test
    @DisplayName("成本估算：tokens/1000×单价 各向取整后相加（TC-AI-10 手工可复核口径）")
    void costEstimateFormula() {
        // 输入 0.001 元/千token、输出 0.002 元/千token：1500 in + 500 out
        when(configReader.pricePer1kPrompt()).thenReturn(new BigDecimal("0.001"));
        when(configReader.pricePer1kCompletion()).thenReturn(new BigDecimal("0.002"));

        service.recordSuccess(1L, BizType.SUMMARY, 9L, "m", 1500, 500, 100, 0, false, "h");
        AiCallLog row = capturedRow();
        // 1500/1000*0.001 = 0.0015；500/1000*0.002 = 0.001 → 合计 0.0025（6 位小数口径）
        assertThat(row.getCostEst()).isEqualByComparingTo("0.002500");

        Mockito.reset(logMapper);
        // tokens 缺失（部分厂商不回 usage）→ 该侧记 0，绝不允许 null 成本炸统计
        service.recordSuccess(1L, BizType.TAGS, 9L, "m", null, 500, 100, 0, false, "h");
        assertThat(capturedRow().getCostEst()).isEqualByComparingTo("0.001000");
    }

    @Test
    @DisplayName("缓存命中成本恒 0：即便单价配了钱，命中行也不许计价（审计成本归零）")
    void cacheHitCostZero() {
        when(configReader.pricePer1kPrompt()).thenReturn(new BigDecimal("9.99"));
        when(configReader.pricePer1kCompletion()).thenReturn(new BigDecimal("9.99"));

        service.recordSuccess(1L, BizType.SUMMARY, 9L, "m", 100000, 100000, 2, 0, true, "h");
        AiCallLog row = capturedRow();
        assertThat(row.getCostEst()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(row.getCacheHit()).isEqualTo(1);
    }

    @Test
    @DisplayName("NOT NULL 哨兵：configId/model 缺失写 0/'unknown'，防插入失败吞掉整行审计")
    void sentinelDefaults() {
        when(configReader.pricePer1kPrompt()).thenReturn(BigDecimal.ZERO);
        when(configReader.pricePer1kCompletion()).thenReturn(BigDecimal.ZERO);

        service.recordSuccess(1L, BizType.TAGS, null, null, 0, 0, 50, 0, false, null);
        AiCallLog row = capturedRow();
        assertThat(row.getAiConfigId()).isZero();
        assertThat(row.getModel()).isEqualTo("unknown");
    }

    // ==================== 失败路径：脱敏与截断 ====================

    @Test
    @DisplayName("失败记账：status=2 + error_msg 的 sk- Key 回声一律替换为 sk-****（NFR-04 红线）")
    void failureMsgSanitized() {
        service.recordFailure(1L, BizType.CONNECT_TEST, 9L, "m", 300, 0,
                "上游返回 HTTP 401: {\"error\":\"invalid api key sk-deadbeefdeadbeefdeadbeefdeadbeef\"}", "h");

        AiCallLog row = capturedRow();
        assertThat(row.getStatus()).isEqualTo(2);
        assertThat(row.getErrorMsg()).doesNotContain("sk-deadbeef")
                .contains("sk-****");
    }

    @Test
    @DisplayName("error_msg 超 500 字符截断至列宽（上游整页 HTML 错误体不许撑爆日志行）")
    void failureMsgTruncated() {
        String huge = "x".repeat(2000);
        service.recordFailure(1L, BizType.SUMMARY, 9L, "m", 10, 0, huge, "h");

        AiCallLog row = capturedRow();
        assertThat(row.getErrorMsg()).hasSize(500);
        // null 消息允许（透传 null，列可空）
        Mockito.reset(logMapper);
        service.recordFailure(1L, BizType.SUMMARY, 9L, "m", 10, 0, null, "h");
        assertThat(capturedRow().getErrorMsg()).isNull();
    }

    @Test
    @DisplayName("记账写失败静默吞（AI 结果已产生，审计缺失不许反杀用户体验）")
    void insertFailureIsSwallowed() {
        doThrow(new RuntimeException("db down")).when(logMapper).insert(any(AiCallLog.class));
        when(configReader.pricePer1kPrompt()).thenReturn(BigDecimal.ZERO);
        when(configReader.pricePer1kCompletion()).thenReturn(BigDecimal.ZERO);

        service.recordSuccess(1L, BizType.SUMMARY, 9L, "m", 1, 1, 5, 0, false, "h");
        service.recordFailure(1L, BizType.SUMMARY, 9L, "m", 5, 0, "boom", "h");
        // 两次都没抛出即通过；insert 确实各被尝试了一次
        verify(logMapper, Mockito.times(2)).insert(any(AiCallLog.class));
    }

    // ==================== stats 聚合（Java 侧算术的口径钉子） ====================

    private AiCallLog logRow(Long userId, String biz, int status, int cacheHit,
                             String cost, int latency, LocalDate day) {
        AiCallLog r = new AiCallLog();
        r.setUserId(userId);
        r.setBizType(biz);
        r.setStatus(status);
        r.setCacheHit(cacheHit);
        r.setCostEst(new BigDecimal(cost));
        r.setLatencyMs(latency);
        r.setCreatedAt(day.atTime(10, 0));
        return r;
    }

    @Test
    @DisplayName("stats：byDay 降序/byBiz 均值/命中率分母含缓存行/今日成本只算今天")
    void statsAggregation() {
        LocalDate today = LocalDate.now();
        LocalDate yesterday = today.minusDays(1);
        when(logMapper.selectList(any())).thenReturn(List.of(
                logRow(1L, "SUMMARY", 1, 0, "0.001000", 100, today),
                logRow(1L, "SUMMARY", 1, 1, "0", 5, today),               // 缓存命中
                logRow(2L, "TAGS", 2, 0, "0", 200, today),                // 失败
                logRow(2L, "TAGS", 3, 0, "0.003000", 900, yesterday),     // 重试后成功
                logRow(9L, "SUMMARY", 1, 0, "0.005000", 50, yesterday)));
        SysUser u1 = new SysUser();
        u1.setId(1L);
        u1.setNickname("张三");
        SysUser u2 = new SysUser();
        u2.setId(2L);
        u2.setNickname("李四");
        when(userMapper.selectBatchIds(any())).thenReturn(List.of(u1, u2));

        AiStatsVo vo = service.stats(yesterday, today);

        assertThat(vo.getTotalCalls()).isEqualTo(5);
        // 命中率 = 缓存命中行 / 全部行 = 1/5 = 0.2（分母含命中行——口径注释即测试断言）
        assertThat(vo.getCacheHitRate()).isEqualByComparingTo("0.2000");
        // todayCost 只累计今天：0.001 + 0 + 0 = 0.001
        assertThat(vo.getTodayCost()).isEqualByComparingTo("0.001");
        // byDay 键序=日期降序（最新在前，前端直接画条形）
        assertThat(vo.getByDay()).extracting(m -> m.get("date"))
                .containsExactly(today.toString(), yesterday.toString());
        // 昨天桶：3 calls（1 直成 + 1 重试成功 + 1 昨日 summary 成功？不——昨日两行）
        // 逐桶核数：昨天 = u2 TAGS status3 + u9 SUMMARY = 2 calls、成功 2、失败 0、成本 0.008
        var yDay = vo.getByDay().get(1);
        assertThat(((Number) yDay.get("calls")).longValue()).isEqualTo(2);
        assertThat(((Number) yDay.get("success")).longValue()).isEqualTo(2);
        assertThat((BigDecimal) yDay.get("cost")).isEqualByComparingTo("0.008000");
        // byBiz：TAGS 2 次（avgLatency=(200+900)/2=550），SUMMARY 3 次
        assertThat(vo.getByBiz()).containsKeys("TAGS", "SUMMARY");
        assertThat(((Number) vo.getByBiz().get("TAGS").get("avgLatencyMs")).longValue()).isEqualTo(550);
        // topUsers 按成本降序：昨日 u9 0.005 > u2 0.003 > u1 0.001
        assertThat(vo.getTopUsers()).extracting(m -> m.get("nickname"))
                .containsExactly("已注销用户", "李四", "张三");   // u9 未命中昵称批量查→占位文案
    }

    @Test
    @DisplayName("stats 空窗口：不抛异常、命中率 0、序列全空（新系统首日起跑不炸看板）")
    void statsEmptyWindow() {
        when(logMapper.selectList(any())).thenReturn(List.of());

        AiStatsVo vo = service.stats(LocalDate.now().minusDays(7), LocalDate.now());
        assertThat(vo.getTotalCalls()).isZero();
        assertThat(vo.getCacheHitRate()).isEqualByComparingTo("0");
        assertThat(vo.getByDay()).isEmpty();
        assertThat(vo.getTopUsers()).isEmpty();
        verify(userMapper, never()).selectBatchIds(any());
    }
}
