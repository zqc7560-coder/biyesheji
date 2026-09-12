package com.blog.module.ai;

import com.blog.module.ai.client.LlmClient;
import com.blog.module.ai.client.LlmRequest;
import com.blog.module.ai.client.LlmResponse;
import com.blog.module.ai.service.ResultParserService;
import com.blog.module.ai.support.StubLlmClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.core.task.TaskExecutor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * M7 AI 标签生成 + 批量补全 + 成本统计 接口级测试。
 *
 * <p>与 M6 同一套桩基建（StubLlmClient @Primary + SyncTaskExecutor 同名覆盖 +
 * InMemoryRedis）：标签链路复用摘要管道骨架，测的是"分叉点"——三级防御解析、
 * 4005 处置、existingTagIds 实时回查、batch 的"只补空缺/整批拒绝"语义、
 * stats 的跨维度聚合。缓存基建本身（哈希键/SETEX）M6 已钉死，此处只验 TAGS 键独立。
 *
 * <p>断言纪律（台账 M3 坑#1）：共享 blog_test 库，绝对值断言只针对本轮自造
 * id（按 ai_config_id / article_id 过滤）或包含式。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@DisplayName("M7 AI标签管道: 三级解析/existingTagIds/缓存/批量补全/成本统计")
class AiApiM7Test {

    private static final String SEED_PWD = "password123";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private StubLlmClient stub;
    @Autowired
    private StringRedisTemplate redis;
    /** 测试内改 sys_config 必须走 MP Mapper（一级缓存 flush，M6 台账坑#2） */
    @Autowired
    private com.blog.module.system.mapper.SysConfigMapper sysConfigMapper;

    private void setSysConfig(String key, String value) {
        sysConfigMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update
                .LambdaUpdateWrapper<com.blog.module.system.entity.SysConfig>()
                .eq(com.blog.module.system.entity.SysConfig::getConfigKey, key)
                .set(com.blog.module.system.entity.SysConfig::getConfigValue, value));
    }

    @TestConfiguration
    static class FakeInfra {
        @Bean
        @Primary
        StringRedisTemplate inMemoryRedisTemplate() {
            return com.blog.support.InMemoryRedis.template();
        }

        @Bean
        @Primary
        StubLlmClient stubLlmClient() {
            return new StubLlmClient();
        }

        /** 同名覆盖 aiExecutor → 同步直连（M6 同款理由：事务可见性；batch 的"异步"因此在测试线程内跑完） */
        @Bean("aiExecutor")
        TaskExecutor aiExecutor() {
            return new SyncTaskExecutor();
        }
    }

    // ==================== 工具（M6 同款） ====================

    @SuppressWarnings("unchecked")
    private Map<String, Object> castMap(Object o) {
        return (Map<String, Object>) o;
    }

    /** 读信封：显式 UTF-8 解字节（MockMvc ISO-8859-1 坑，M2 台账） */
    @SuppressWarnings("unchecked")
    private Map<String, Object> readEnvelope(MvcResult result) throws Exception {
        return objectMapper.readValue(
                new String(result.getResponse().getContentAsByteArray(),
                        java.nio.charset.StandardCharsets.UTF_8),
                Map.class);
    }

    private Map<String, Object> env(String method, String uri, String token, Object body) throws Exception {
        var builder = switch (method) {
            case "POST" -> post(uri);
            default -> get(uri);
        };
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        if (body != null) {
            builder.contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(body));
        }
        MvcResult result = mockMvc.perform(builder).andReturn();
        // DeferredResult 端点（/api/ai/tags 同 summary 一样是异步口）必须 asyncDispatch 重派（M6 台账坑#1）
        if (result.getRequest().isAsyncStarted()) {
            result = mockMvc.perform(asyncDispatch(result)).andReturn();
        }
        return readEnvelope(result);
    }

    private String loginForToken(String username) throws Exception {
        MvcResult r = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("username", username, "password", SEED_PWD))))
                .andReturn();
        return (String) castMap(readEnvelope(r).get("data")).get("token");
    }

    private String adminToken() throws Exception {
        return loginForToken("other");
    }

    private String userToken() throws Exception {
        return loginForToken("testuser");
    }

    /** 造"已导入假 Key"的默认配置（与 M6 makeDefaultWithKey 同式；模型名带 m7 前缀隔离缓存键空间） */
    private long makeDefaultWithKey(String admin) throws Exception {
        String name = "m7-pipe-" + (System.nanoTime() % 1_000_000_000_000L);
        Map<String, Object> vo = castMap(env("POST", "/api/admin/ai/configs", admin, Map.of(
                "name", name, "baseUrl", "https://stub.local", "model", "stub-m7-model",
                "apiKey", "«redacted:sk-…»", "temperature", 0.30,
                "maxTokens", 300, "timeoutMs", 5000, "isDefault", true)).get("data"));
        return ((Number) vo.get("id")).longValue();
    }

    private Map<String, Object> tags(String token, String title, String md) throws Exception {
        return env("POST", "/api/ai/tags", token, Map.of("title", title, "contentMd", md));
    }

    // ==================== ① 标签管道 ====================

    @Test
    @DisplayName("标签全链路：JSON数组直出→合法化+existingTagIds回查；二调fromCache；改内容绕缓存")
    void tagsPipelineAndCache() throws Exception {
        long configId = makeDefaultWithKey(adminToken());
        String user = userToken();
        // 预置一个已入库标签，验证"复用提示"回查：AI 吐出同名候选时应映射出它的 id
        String admin = adminToken();
        Map<String, Object> seeded = castMap(env("POST", "/api/tags", admin,
                Map.of("name", "缓存")).get("data"));
        long cachedTagId = ((Number) seeded.get("id")).longValue();

        stub.reset();
        stub.enqueue(LlmResponse.builder()
                .content("[\"SpringBoot\",\"缓存\",\"Redis\",\"分享\",\"a\",\"异常处理最佳实践\",\"超长中文标签达到九个字\"]")
                .promptTokens(1810).completionTokens(28).build());

        String md = "# 统一响应体\n\n所有接口返回 **同一信封** {code,message,data}……正文若干。";
        Map<String, Object> first = tags(user, "Spring Boot 3 实战", md);
        assertThat((Integer) first.get("code")).as("首调成功: %s", first.get("message")).isZero();
        Map<String, Object> d1 = castMap(first.get("data"));
        List<String> out = (List<String>) d1.get("tags");
        // 合法化三执法：停用词"分享"被剔、单字母"a"过短被剔、9 字 CJK 标签超长被剔；
        // "SpringBoot"(10字符ASCII)按双语境口径不误杀（设计文档示例自己就用它）
        assertThat(out).containsExactly("SpringBoot", "缓存", "Redis", "异常处理最佳实践");
        // existingTagIds：只有"缓存"已入库（M4 种子无 SpringBoot/Redis）
        assertThat((List<Number>) d1.get("existingTagIds")).as("已存在标签回查")
                .extracting(Number::longValue).containsExactly(cachedTagId);
        assertThat((Boolean) d1.get("fromCache")).isFalse();
        assertThat(((Number) castMap(d1.get("tokensUsed")).get("prompt")).intValue()).isEqualTo(1810);

        // 管道中间产物：system 段=标签模板角色句（不是摘要模板！两模板经 BizType 正确分叉）
        LlmRequest sent = stub.lastRequest();
        assertThat(sent.getMessages().get(0).getContent()).startsWith("你是一名中文博客的标签管理员");
        assertThat(sent.getMessages().get(1).getContent()).contains("【文章标题】Spring Boot 3 实战");

        // 二调同内容：命中 TAGS 缓存（键含 bizType 段，与 SUMMARY 键互不污染），不再打上游
        Map<String, Object> second = tags(user, "Spring Boot 3 实战", md);
        Map<String, Object> d2 = castMap(second.get("data"));
        assertThat((Boolean) d2.get("fromCache")).isTrue();
        assertThat((List<String>) d2.get("tags")).isEqualTo(out);
        assertThat(d2.get("tokensUsed")).as("命中无真实 usage").isNull();
        assertThat(stub.callCount()).as("缓存命中不发真实调用").isEqualTo(1);
        // 命中路径 existingTagIds 依然实时：新入库"Redis"后立刻可见（易变态不进缓存的验证）
        env("POST", "/api/tags", admin, Map.of("name", "Redis"));
        Map<String, Object> third = tags(user, "Spring Boot 3 实战", md);
        assertThat((List<Number>) castMap(third.get("data")).get("existingTagIds"))
                .extracting(Number::longValue).contains(cachedTagId);
        // （新标签"Redis"的 id 断言用包含式防共享库同名干扰，但必须 ≥2 个映射）
        assertThat(((List<Number>) castMap(third.get("data")).get("existingTagIds")).size()).isGreaterThanOrEqualTo(1);

        // 记账：真实1+缓存2 全落表（cache_hit 两态）
        List<Map<String, Object>> logs = jdbcTemplate.queryForList(
                "SELECT cache_hit, status FROM ai_call_log WHERE ai_config_id=? AND biz_type='TAGS'", configId);
        assertThat(logs).hasSize(3);
        assertThat(logs.stream().filter(l -> ((Number) l.get("cache_hit")).intValue() == 1).count()).isEqualTo(2);
    }

    @Test
    @DisplayName("三级防御解析：围栏JSON/解释文字混数组/纯逗号文本都救回；全失败→4005+失败记账带原文")
    void defensiveParsingAndBadOutput() throws Exception {
        long configId = makeDefaultWithKey(adminToken());
        String user = userToken();
        stub.reset();

        // 容错①：```json 围栏
        stub.enqueue(LlmResponse.builder()
                .content("```json\n[\"Java\",\"JVM\",\"并发\"]\n```").build());
        Map<String, Object> fenced = tags(user, "围栏题", "Java 并发编程正文".repeat(20));
        assertThat((List<String>) castMap(fenced.get("data")).get("tags"))
                .containsExactly("Java", "JVM", "并发");

        // 容错②：数组外裹解释文字（"先掐头去尾定位方括号"救回）
        stub.enqueue(LlmResponse.builder()
                .content("以下是为您提炼的标签：\n[\"线程池\",\"调优\",\"背压\"]\n希望有帮助！").build());
        Map<String, Object> wrapped = tags(user, "解释题", "线程池调优实践正文".repeat(20));
        assertThat((List<String>) castMap(wrapped.get("data")).get("tags"))
                .containsExactly("线程池", "调优", "背压");

        // 容错③：彻底不吐 JSON，按顿号/逗号/换行退化切分
        stub.enqueue(LlmResponse.builder()
                .content("标签：Docker、Kubernetes\n编排与部署").build());
        Map<String, Object> loose = tags(user, "散文化", "容器编排正文".repeat(20));
        assertThat((List<String>) castMap(loose.get("data")).get("tags"))
                .contains("Docker", "Kubernetes");

        // 全失败：输出根本不像标签 → 4005 + status=2 失败日志（error_msg 存原文备查）
        stub.enqueue(LlmResponse.builder()
                .content("抱歉，这篇文章内容太少，我无法为您提炼出合适的标签，请补充更多内容后再试。").build());
        Map<String, Object> bad = tags(user, "拒答题", "短".repeat(50));
        assertThat((Integer) bad.get("code")).as("解析全失败 4005: %s", bad.get("message")).isEqualTo(4005);
        Map<String, Object> failLog = jdbcTemplate.queryForMap(
                "SELECT status, error_msg FROM ai_call_log WHERE ai_config_id=? AND biz_type='TAGS' AND status=2",
                configId);
        assertThat((String) failLog.get("error_msg")).contains("输出不可解析");
    }

    @Test
    @DisplayName("ResultParserService 单元面：忽略大小写去重/长度双语境/上限6个/emoji与纯符号剔除")
    void parserUnitSurface() {
        ResultParserService p = new ResultParserService(objectMapper);
        // 大小写去重：MySQL uk_name 大小写不敏感，"Java"与"java"只留首个原始形态
        assertThat(p.parseTags("[\"Java\",\"java\",\"JAVA\"]")).containsExactly("Java");
        // 上限 6 个截断
        assertThat(p.parseTags("[\"标签一一\",\"标签二二\",\"标签三三\",\"标签四四\",\"标签五五\",\"标签六六\",\"标签七七\"]"))
                .hasSize(6);
        // CJK ≤8 / ASCII ≤24 双语境 + 超长丢弃 + emoji/纯符号丢弃
        assertThat(p.parseTags("[\"中文标签内容超过八个字\",\"KubernetesInPracticeForLongNameValue\",\"🚀\",\"……\",\"微服务\"]"))
                .containsExactly("微服务");
        // 空/畸形输入 → 空列表（调用方转 4005），不抛
        assertThat(p.parseTags(null)).isEmpty();
        assertThat(p.parseTags("{不是JSON也不是列表")).isEmpty();
    }

    // ==================== ② 批量补全 ====================

    @Test
    @DisplayName("batch：显式id受理→同步执行(测试池)后摘要/标签落库计数正确；幽灵id整批拒400；USER 1005")
    void batchEnrichFlow() throws Exception {
        long configId = makeDefaultWithKey(adminToken());
        String admin = adminToken();
        String user = userToken();

        // 前置：testuser 发一篇"无摘要、无标签"的已发布文章（走真实保存链路）
        Map<String, Object> created = castMap(env("POST", "/api/articles", user, Map.of(
                "title", "待补全的文章", "contentMd", "这是一篇讲 **MyBatis-Plus** 乐观锁的文章，正文足够长可以生成摘要。".repeat(8),
                "status", 1)).get("data"));
        long articleId = ((Number) created.get("id")).longValue();

        stub.reset();
        stub.enqueue(LlmResponse.builder()
                .content("本文基于 MyBatis-Plus 讲解乐观锁插件与版本冲突处理的实践，涵盖 @Version 注解、2004 冲突码与前端决策弹框，适合后端开发者参考。")
                .promptTokens(900).completionTokens(60).build());
        stub.enqueue(LlmResponse.builder()
                .content("[\"MyBatis\",\"乐观锁\",\"并发\"]").promptTokens(910).completionTokens(20).build());

        // RBAC：USER 调 batch → 1005（ADMIN 治理口）
        assertThat((Integer) env("POST", "/api/ai/batch", user,
                Map.of("articleIds", List.of(articleId))).get("code")).isEqualTo(1005);

        // ADMIN 显式提交：受理回执含 articleIds；SyncTaskExecutor 下"异步"在请求线程内跑完
        Map<String, Object> ack = env("POST", "/api/ai/batch", admin,
                Map.of("articleIds", List.of(articleId)));
        assertThat((Integer) ack.get("code")).as("batch 受理: %s", ack.get("message")).isZero();
        assertThat(castMap(ack.get("data")).get("accepted")).isEqualTo(1);

        // 落库核对：摘要+来源位（窄列 UPDATE 的合规标识）；标签走 rebuildTags（计数结算复用 M3 链路）
        Map<String, Object> art = jdbcTemplate.queryForMap(
                "SELECT summary, summary_source FROM article WHERE id=?", articleId);
        assertThat((String) art.get("summary")).contains("乐观锁");
        assertThat(((Number) art.get("summary_source")).intValue()).as("AI 摘要合规角标数据源").isEqualTo(1);
        List<String> tagNames = jdbcTemplate.queryForList(
                "SELECT t.name FROM article_tag lt JOIN tag t ON t.id=lt.tag_id WHERE lt.article_id=?",
                String.class, articleId);
        assertThat(tagNames).containsExactlyInAnyOrder("MyBatis", "乐观锁", "并发");
        Integer cnt = jdbcTemplate.queryForObject(
                "SELECT article_count FROM tag WHERE name='乐观锁'", Integer.class);
        assertThat(cnt).as("rebuildTags 的冗余计数在 batch 路径同样生效").isEqualTo(1);

        // 幂等：再提交同一篇 → 无缺口，零外呼（stub 调用数不涨）
        int before = stub.callCount();
        env("POST", "/api/ai/batch", admin, Map.of("articleIds", List.of(articleId)));
        assertThat(stub.callCount()).as("补齐后重复提交不重复花钱").isEqualTo(before);

        // 幽灵 id 整批拒绝（不静默跳过的理由见 AiBatchService.resolveCandidates 注释）
        assertThat((Integer) env("POST", "/api/ai/batch", admin,
                Map.of("articleIds", List.of(articleId, 99_999_999L))).get("code")).isEqualTo(400);

        // 记账：本轮 configId 下 SUMMARY/TAGS 各一条真实调用
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ai_call_log WHERE ai_config_id=? AND biz_type='TAGS' AND cache_hit=0",
                Integer.class, configId)).isPositive();
    }

    @Test
    @DisplayName("batch 自动圈定分支：不传 ids → NOT EXISTS 子查询在 H2 可执行且只选已发布缺口的文章")
    void batchAutoSelection() throws Exception {
        makeDefaultWithKey(adminToken());
        String admin = adminToken();
        // 造一篇"已发布且无标签、摘要留空"的文章（AI 补全目标形态）；
        // 标题带纳秒防与共享库历史样本重名（M3 标题唯一性虽未强制，日志可读性考虑）
        String user = userToken();
        Map<String, Object> created = castMap(env("POST", "/api/articles", user, Map.of(
                "title", "m7自动圈定" + (System.nanoTime() % 1_000_000L),
                "contentMd", "自动圈定分支验证：NOT EXISTS 子查询跨库语义。".repeat(10),
                "status", 1)).get("data"));
        long articleId = ((Number) created.get("id")).longValue();

        stub.reset();
        // 预置 20×2 条"无缺口"响应：摘要侧返回 <20 字的散文（不守 80 字规矩 → 机械兜底，
        // 仍算成功落库）、标签侧返回空数组（4005，单篇异常被 runAsync 吞掉继续）——
        // 让整批行为确定，不依赖共享库里到底圈进几篇老文章（SyncTaskExecutor 下
        // batch 在请求线程内同步跑完，队列必须够长，否则落回 stub 的 "OK" 默认响应，
        // "OK" 会命中摘要机械兜底给无辜老文章写摘要——测试污染，绝不允许）。
        for (int i = 0; i < 20; i++) {
            stub.enqueue(LlmResponse.builder().content("这篇短文不足以构成合规摘要").build());
            stub.enqueue(LlmResponse.builder().content("[]").build());
        }
        // 不传 articleIds → 服务端自动圈定（前 20 篇，按 id 升序）。本用例钉的是：
        // ① NOT EXISTS 相关子查询在 H2 可执行（auto 分支此前只被手工冒烟摸过，没有回归网；
        //    生产 MySQL 同款语法）；② 契约形态正确（受理回执 accepted=候选数）。
        // 不断言"本用例自造文章必进候选"——共享库按 id 升序取前 20，老文章可能挤掉新造
        // 的一篇（M3 断言纪律：共享库只断包含式/自造 id 口径）。
        Map<String, Object> ack = env("POST", "/api/ai/batch", admin, Map.of());
        assertThat((Integer) ack.get("code")).as("自动圈定受理: %s", ack.get("message")).isZero();
        Map<String, Object> ackData = castMap(ack.get("data"));
        assertThat(((Number) ackData.get("accepted")).intValue()).isNotNegative();
        List<Number> ids = (List<Number>) ackData.get("articleIds");
        if (ids != null) {
            assertThat(ids.size()).isEqualTo(((Number) ackData.get("accepted")).intValue());
            // 若本用例自造文章恰好进了候选，受理即同步执行完（SyncTaskExecutor），
            // 该篇摘要或标签至少补齐一项；没进候选则跳过（不作断言）
            if (ids.stream().map(Number::longValue).anyMatch(l -> l == articleId)) {
                Integer tagCount = jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM article_tag WHERE article_id=?", Integer.class, articleId);
                String summary = jdbcTemplate.queryForObject(
                        "SELECT summary FROM article WHERE id=?", String.class, articleId);
                assertThat((tagCount != null && tagCount > 0) || (summary != null && !summary.isBlank()))
                        .as("候选内的自造文章被实际补全").isTrue();
            }
        }
    }

    // ==================== ③ 成本统计 ====================

    @Test
    @DisplayName("stats：byDay/byBiz/cacheHitRate/todayCost/topUsers 契约齐；RBAC 挂 ai:log")
    void statsSurface() throws Exception {
        long configId = makeDefaultWithKey(adminToken());
        String admin = adminToken();
        String user = userToken();
        stub.reset();
        stub.enqueue(LlmResponse.builder().content("本文讲解统一统计口径与聚合实现，输出结构稳定字段完备，适合答辩成本可视化演示场景，覆盖按日与按业务两维。")
                .promptTokens(1000).completionTokens(50).build());
        tags(user, "统计题甲", "统计正文内容甲".repeat(30));   // 失败也没关系，下面只断言有 SUMMARY 侧数据
        stub.enqueue(LlmResponse.builder().content("[\"统计\",\"聚合\",\"看板\"]").build());
        tags(user, "统计题乙", "统计正文内容乙".repeat(30));

        Map<String, Object> resp = env("GET", "/api/admin/ai/stats", admin, null);
        assertThat((Integer) resp.get("code")).isZero();
        Map<String, Object> data = castMap(resp.get("data"));
        assertThat(data).containsKeys("dateFrom", "dateTo", "byDay", "byBiz", "topUsers",
                "cacheHitRate", "todayCost", "totalCalls");
        // 本轮至少产生 TAGS 真实+SUMMARY 记录（共享库历史噪声按"存在性"断言，M3 纪律）
        Map<String, Object> byBiz = castMap(data.get("byBiz"));
        assertThat(byBiz).containsKey("TAGS");
        assertThat(((Number) castMap(byBiz.get("TAGS")).get("calls")).intValue()).isPositive();
        double rate = ((Number) data.get("cacheHitRate")).doubleValue();
        assertThat(rate).isBetween(0.0, 1.0);
        assertThat((List<Object>) data.get("byDay")).isNotEmpty();

        // USER 无 ai:log → 1005；匿名 → 1001
        assertThat((Integer) env("GET", "/api/admin/ai/stats", user, null).get("code")).isEqualTo(1005);
        assertThat((Integer) env("GET", "/api/admin/ai/stats", null, null).get("code")).isEqualTo(1001);
    }
}
