package com.blog.module.ai.service;

import com.blog.common.BizException;
import com.blog.module.ai.client.LlmResponse;
import com.blog.module.ai.enums.BizType;
import com.blog.support.InMemoryRedis;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * M11 测试完善 · AI 缓存键 / 内容哈希 / 日配额三件套单测
 * （《部署与测试.md》§2 单测范围明示项："内容归一化哈希、配额计算"）。
 *
 * <p>这三块是 AI 模块里"错一子全盘活错"的算术层：
 * <ul>
 *   <li>contentHash 不稳 → 同文反复付费或改文仍吃旧缓存（TC-AI-02/03 的根）；</li>
 *   <li>buildKey 维度缺失 → 换模型/换模板/换温度会互相污染结果；</li>
 *   <li>配额闸门 off-by-one → 用户被多拦一次（体验）或多放一次（成本）。</li>
 * </ul>
 * 接口级测试（AiApiM6/M7Test）只在管道层面点到"命中/未命中"，钉不住这些
 * 逐字符、逐边界的行为——正是本类的职责（JaCoCo 的 AI 包缺口也集中在这里：
 * 纯函数分支多、被大测试路径盖住的只有主干）。
 *
 * <p>基建复用 InMemoryRedis（M2 起认证/缓存测试标配），零真中间件依赖。
 */
@DisplayName("M11 AI算术层: 内容哈希/五段键/日配额")
class AiCacheQuotaM11Test {

    private StringRedisTemplate redis;
    private AiCacheService cache;
    private AiSysConfigReader configReader;
    private AiQuotaService quota;

    @BeforeEach
    void setUp() {
        redis = InMemoryRedis.template();
        cache = new AiCacheService(redis, new ObjectMapper());
        // 配置读取器 mock：配额值由用例逐条控制，不吃种子库
        configReader = Mockito.mock(AiSysConfigReader.class);
        quota = new AiQuotaService(redis, configReader);
    }

    // ==================== 内容归一化哈希（《AI模块设计.md》§6.1） ====================

    @Test
    @DisplayName("空白/缩进/换行差异不改哈希：'换个排版'不该触发重新付费")
    void whitespaceInsensitive() {
        // 口径说明：《AI模块设计.md》§6.1 的公式是 Java 正则 \s+，语义只覆盖 ASCII 空白
        // （制表/换行/半角空格），**不含全角空格 U+3000**——实现逐字对齐文档，本用例
        // 也钉在同一口径上。全角缩进改版会换哈希是已知设计边界，不是 bug；
        // 若未来扩公式（如 [\s\u3000]+），等于全体历史缓存作废，必须走显式评审改文档。
        String a = AiCacheService.contentHash("标题", "第一段\n\n  第二段\t带多余空格  第三段\r\n");
        String b = AiCacheService.contentHash("标题", "第一段第二段带多余空格第三段");
        assertThat(a).isEqualTo(b);
    }

    @Test
    @DisplayName("图片语法剔除：换一张图/删一张图不改变文本实质（§6.1 公式的第二条清洗）")
    void imageSyntaxIgnored() {
        String withImg = AiCacheService.contentHash("T", "正文开头![alt图注](https://cdn/x.png)正文结尾");
        String noImg = AiCacheService.contentHash("T", "正文开头正文结尾");
        assertThat(withImg).isEqualTo(noImg);
    }

    @Test
    @DisplayName("大小写归一（toLowerCase 后进哈希）：纯大小写改版不重复计费")
    void caseInsensitive() {
        assertThat(AiCacheService.contentHash("T", "Spring Cache 实践"))
                .isEqualTo(AiCacheService.contentHash("T", "spring cache 实践"));
    }

    @Test
    @DisplayName("实质改一字必换哈希（TC-AI-03 的哈希根因）；标题改动同样换键")
    void substantiveChangeFlipsHash() {
        String base = AiCacheService.contentHash("T", "这篇文章讲缓存穿透的三种解法");
        assertThat(AiCacheService.contentHash("T", "这篇文章讲缓存穿透的两种解法")).isNotEqualTo(base);
        assertThat(AiCacheService.contentHash("T2", "这篇文章讲缓存穿透的三种解法")).isNotEqualTo(base);
    }

    @Test
    @DisplayName("哈希输出形态：SHA-256 hex 恒 64 小写十六进制（进 content_hash CHAR(64) 列）")
    void hashShape() {
        String h = AiCacheService.contentHash("任意标题", "任意内容 https://x.y/z");
        assertThat(h).hasSize(64).matches("[0-9a-f]{64}");
        // 已知向量自校验：对固定输入钉死输出，防止归一化公式被"顺手改动"而无人发现
        // （公式=sha256(title + \n + 归一化正文)；改动哈希公式=全部历史缓存作废，必须是显式决策）
        assertThat(AiCacheService.contentHash("a", "b")).isEqualTo(sha256HexKnown("a\nb"));
    }

    /** 期望向量的独立算路：直接对拼接串取 SHA-256（绕过被测类的归一化步骤，两路一致才算钉死） */
    private static String sha256HexKnown(String s) {
        try {
            byte[] d = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(d);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("null 标题按空串参与拼接（不抛 NPE——模板装配前 title 可能未填）")
    void nullTitleSafe() {
        assertThat(AiCacheService.contentHash(null, "abc"))
                .isEqualTo(AiCacheService.contentHash("", "abc"));
    }

    // ==================== 五段式键装配 ====================

    @Test
    @DisplayName("键=ai:{biz}:{hash}:{model}:{promptVer}:{temp}，温度恒两位小数（0.3 与 0.30 同键）")
    void buildKeyShape() {
        String k1 = cache.buildKey(BizType.SUMMARY, "h1", "deepseek-v4-flash", "v1", new BigDecimal("0.3"));
        String k2 = cache.buildKey(BizType.SUMMARY, "h1", "deepseek-v4-flash", "v1", new BigDecimal("0.300"));
        assertThat(k1).isEqualTo(k2).isEqualTo("ai:summary:h1:deepseek-v4-flash:v1:0.30");
    }

    @Test
    @DisplayName("温度缺省 0.30（null 配置与显式 0.3 必须同键，否则缓存永远 miss）")
    void nullTemperatureDefaults() {
        assertThat(cache.buildKey(BizType.TAGS, "h", "m", "v", null))
                .isEqualTo(cache.buildKey(BizType.TAGS, "h", "m", "v", new BigDecimal("0.3")));
    }

    @Test
    @DisplayName("四维度任一变化都换键：biz/model/promptVer/temp 各自独立成段")
    void everyDimensionIsolates() {
        String base = cache.buildKey(BizType.SUMMARY, "h", "m", "v", new BigDecimal("0.3"));
        assertThat(cache.buildKey(BizType.TAGS, "h", "m", "v", new BigDecimal("0.3"))).isNotEqualTo(base);
        assertThat(cache.buildKey(BizType.SUMMARY, "h", "m2", "v", new BigDecimal("0.3"))).isNotEqualTo(base);
        assertThat(cache.buildKey(BizType.SUMMARY, "h", "m", "v2", new BigDecimal("0.3"))).isNotEqualTo(base);
        assertThat(cache.buildKey(BizType.SUMMARY, "h", "m", "v", new BigDecimal("0.7"))).isNotEqualTo(base);
    }

    @Test
    @DisplayName("读写往返 + 异常降级：get 未命中回 null；Redis 抛异常按 miss/静默处理不炸调用链")
    void getPutAndDegrade() {
        cache.put("ai:summary:k", java.util.Map.of("text", "结果"));
        assertThat(cache.get("ai:summary:k")).contains("结果");
        assertThat(cache.get("不存在")).isNull();

        // 换一套"会炸"的 Redis：读按 miss、写吞异常——§6.2 缓存是加速器不是正确性依赖
        StringRedisTemplate boom = Mockito.mock(StringRedisTemplate.class);
        when(boom.opsForValue()).thenThrow(new RuntimeException("connection refused"));
        AiCacheService degraded = new AiCacheService(boom, new ObjectMapper());
        assertThat(degraded.get("any")).isNull();
        degraded.put("any", "payload");   // 不得抛出
    }

    // ==================== 日配额（《AI模块设计.md》§3.4） ====================

    @Test
    @DisplayName("键按自然日：ai:quota:{uid}:{yyyyMMdd}，今日计数不污染明日口径")
    void quotaKeyIsDaily() {
        when(configReader.dailyQuota()).thenReturn(3);
        quota.consume(42L);
        String todayKey = "ai:quota:42:" + LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        assertThat(redis.opsForValue().get(todayKey)).isEqualTo("1");
        assertThat(quota.current(42L)).isEqualTo(1);
    }

    @Test
    @DisplayName("闸门语义 used>=quota 拒：3 次配额消费 3 次后第 4 次前置判定抛 4003(HTTP 429)")
    void quotaBoundary() {
        when(configReader.dailyQuota()).thenReturn(3);
        for (int i = 0; i < 3; i++) {
            quota.assertWithinQuota(7L);   // 消费前逐次放行
            quota.consume(7L);
        }
        assertThatThrownBy(() -> quota.assertWithinQuota(7L))
                .isInstanceOf(BizException.class)
                .satisfies(e -> {
                    BizException b = (BizException) e;
                    assertThat(b.getBizCode().getCode()).isEqualTo(4003);
                    assertThat(b.getHttpStatus()).isEqualTo(429);
                    assertThat(b.getMessage()).contains("3");   // 文案带上限值，用户知道为什么被拦
                });
    }

    @Test
    @DisplayName("不同用户额度互不相干（多租户隔离）；未消费过的用户 current=0")
    void quotaPerUser() {
        when(configReader.dailyQuota()).thenReturn(1);
        quota.consume(100L);
        assertThat(quota.current(100L)).isEqualTo(1);
        assertThat(quota.current(200L)).isZero();
        quota.assertWithinQuota(200L);   // 200 用户不受 100 的消耗影响
    }

    @Test
    @DisplayName("Redis 故障 fail-open：读失败按 0 放行、计数失败只留痕（§3.4 可用性取舍）")
    void quotaFailsOpen() {
        StringRedisTemplate boom = Mockito.mock(StringRedisTemplate.class);
        when(boom.opsForValue()).thenThrow(new RuntimeException("redis down"));
        when(configReader.dailyQuota()).thenReturn(1);
        AiQuotaService flaky = new AiQuotaService(boom, configReader);

        assertThat(flaky.current(1L)).isZero();           // 读炸 → 当作没用过
        flaky.assertWithinQuota(1L);                      // 不得抛出（放行）
        flaky.consume(1L);                                // 不得抛出（吞计数）
    }
}
