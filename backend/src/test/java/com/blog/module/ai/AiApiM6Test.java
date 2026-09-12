package com.blog.module.ai;

import com.blog.module.ai.client.LlmCallException;
import com.blog.module.ai.client.LlmClient;
import com.blog.module.ai.client.LlmRequest;
import com.blog.module.ai.client.LlmResponse;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * M6 AI 模块接口级测试 —— 课题重点三层架构的验收：
 * ① 配置管理：AES-GCM 密文入库 / 掩码回显 / 内置行保护 / 唯一默认互斥 / RBAC 闸门
 * ② 摘要管道：开关→配额→缓存→真实调用→解析兜底→记账 全链路（桩客户端，零出网）
 * ③ 降级矩阵：4001（未配置/占位Key）/4002（调用失败）/4003（配额）/4004（总开关）/4101/4102
 *
 * <p>LlmClient 桩在接口边界（StubLlmClient）：CI 与断网维护窗口下测试矩阵照跑
 * （R-02 预案）；真实连通性属人工冒烟（台账 M6 备注），不混进自动化。
 *
 * <p>aiExecutor 以同名 Bean 覆盖为 SyncTaskExecutor：MockMvc + 类级 @Transactional
 * 下异步线程拿另一条数据库连接、看不到测试事务预置的 ai_config 行（经典约束）。
 * 同步化后 DeferredResult 任务体在测试线程内跑完，全链路可断言；
 * 35s 超时与线程卸载语义是集成层职责（M12 JMeter 场景验证），不在单元层假测。
 *
 * <p>断言纪律（M3 台账坑#1）：共享 blog_test 库——绝对值断言只针对本轮自造 id
 * （ai_call_log 按 ai_config_id 过滤）或包含式。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@DisplayName("M6 AI模块: 配置加密/内置保护/摘要管道/缓存/配额/降级矩阵")
class AiApiM6Test {

    private static final String SEED_PWD = "password123";
    /** 测试库 AES 主密钥（与 application-test.yml 一致；只保护 sk-TEST-FAKE-* 假值） */
    private static final String TEST_MASTER_HEX =
            "000102030405060708090a0b0c0d0e0f000102030405060708090a0b0c0d0e0f";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private StubLlmClient stub;
    /** 内存 Redis（@Primary）：清理 ai:quota 计数键实现配额用例的确定性 */
    @Autowired
    private StringRedisTemplate redis;
    /** sys_config 写入走 MP Mapper（触发 SqlSession 缓存 flush），见 setSysConfig 注释 */
    @Autowired
    private com.blog.module.system.mapper.SysConfigMapper sysConfigMapper;

    /**
     * 测试内改系统参数：必须走 MyBatis（同一 SqlSession 的写操作会 flush 一级缓存）。
     * JdbcTemplate 的 UPDATE 绕开 MyBatis，缓存里旧值不动——同一测试事务内
     * "JDBC 改了、MP 还读旧"的错位真实咬过 M6（degradeMatrix c 步拿到 4004）。
     */
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

        /** 桩客户端：@Primary 覆盖 OpenAiCompatClient（门面按接口 LlmClient 注入） */
        @Bean
        @Primary
        StubLlmClient stubLlmClient() {
            return new StubLlmClient();
        }

        /** 同名覆盖 aiExecutor → 同步直连（见类注释） */
        @Bean("aiExecutor")
        TaskExecutor aiExecutor() {
            return new SyncTaskExecutor();
        }
    }

    // ==================== 工具（M2~M5 同款） ====================

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
            case "PUT" -> put(uri);
            case "PATCH" -> patch(uri);
            case "DELETE" -> delete(uri);
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
        // M6：/api/ai/summary 是 DeferredResult 异步端点——MockMvc 首轮响应为空，
        // 必须 asyncDispatch 把已完成的异步结果派送回响应再读信封（同一测试线程，
        // SyncTaskExecutor 下任务体已在 perform 内跑完，事务可见性不破坏）
        if (result.getRequest().isAsyncStarted()) {
            result = mockMvc.perform(org.springframework.test.web.servlet.request
                    .MockMvcRequestBuilders.asyncDispatch(result)).andReturn();
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

    /** other(id=2)=种子 ROLE_ADMIN；testuser(id=1)=ROLE_USER（有 ai:use 无 ai:config） */
    private String adminToken() throws Exception {
        return loginForToken("other");
    }

    private String userToken() throws Exception {
        return loginForToken("testuser");
    }

    /** 造一个"已导入假 Key"的启用默认配置（管道用例前置），返回 id */
    private long makeDefaultWithKey(String admin) throws Exception {
        String name = "m6-pipe-" + (System.nanoTime() % 1_000_000_000_000L);
        Map<String, Object> vo = castMap(env("POST", "/api/admin/ai/configs", admin, Map.of(
                "name", name, "baseUrl", "https://stub.local", "model", "stub-model",
                "apiKey", "sk-TEST-FAKE-000111222333", "temperature", 0.30,
                "maxTokens", 300, "timeoutMs", 5000, "isDefault", true)).get("data"));
        return ((Number) vo.get("id")).longValue();
    }

    private Map<String, Object> summary(String token, String title, String md) throws Exception {
        return env("POST", "/api/ai/summary", token, Map.of("title", title, "contentMd", md));
    }

    /** 模型摘要输出的合格样本（≥80 字，避开 <20 兜底判定线） */
    private static String goodSummary() {
        return "本文基于 Spring Boot 3 讲解统一响应体与全局异常处理的最佳实践，"
                + "涵盖 Result 封装、异常分层与错误码设计，适合后端开发者参考。全文结构清晰，"
                + "示例可直接复用于个人项目与团队协作场景。";
    }

    // ==================== ① 配置管理 ====================

    @Test
    @DisplayName("配置CRUD：创建只回掩码；库内为AES密文可解密还原；PUT空Key不变更；重加密产生新密文")
    void configCrudMaskAndCipher() throws Exception {
        String admin = adminToken();
        String name = "m6-" + (System.nanoTime() % 1_000_000_000_000L);
        Map<String, Object> vo = castMap(env("POST", "/api/admin/ai/configs", admin, Map.of(
                "name", name, "provider", "deepseek",
                "baseUrl", "https://api.deepseek.com/", "apiKey", "sk-TEST-FAKE-abcdef123456",
                "model", "deepseek-v4-flash", "temperature", 0.30, "maxTokens", 512,
                "timeoutMs", 30000)).get("data"));

        assertThat(((Number) vo.get("id")).longValue()).isPositive();
        assertThat(vo.get("apiKeyMask")).isEqualTo("sk-****3456");
        // 出参结构红线：明文/密文字段名都不许出现在响应里（§9.1 编译期杜绝的回显验证）
        assertThat(vo).doesNotContainKey("apiKey").doesNotContainKey("apiKeyCipher");
        assertThat(vo.get("keyImported")).isEqualTo(true);

        // 落库形态：cipher 列=GCM 信封（非明文/非占位串）；base_url 尾斜杠规范化
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT api_key_cipher, base_url FROM ai_config WHERE id = ?", vo.get("id"));
        String cipher = (String) row.get("api_key_cipher");
        assertThat(cipher).isNotEqualTo("sk-TEST-FAKE-abcdef123456")
                .isNotEqualTo("__PENDING_KEY_IMPORT__");
        assertThat(com.blog.module.ai.crypto.AesGcmUtil.decrypt(cipher,
                java.util.HexFormat.of().parseHex(TEST_MASTER_HEX)))
                .isEqualTo("sk-TEST-FAKE-abcdef123456");
        assertThat(row.get("base_url")).isEqualTo("https://api.deepseek.com");

        // PUT 空 Key=保持原密文（管理员调参不必重贴 Key）
        env("PUT", "/api/admin/ai/configs/" + vo.get("id"), admin, Map.of(
                "name", name, "baseUrl", "https://api.deepseek.com", "model", "deepseek-v4-flash",
                "apiKey", ""));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT api_key_cipher FROM ai_config WHERE id = ?", String.class, vo.get("id")))
                .isEqualTo(cipher);

        // 同明文重新加密 → 新密文（随机 IV 直接证据）且仍可解回原文
        env("PUT", "/api/admin/ai/configs/" + vo.get("id"), admin, Map.of(
                "name", name, "baseUrl", "https://api.deepseek.com", "model", "deepseek-v4-flash",
                "apiKey", "sk-TEST-FAKE-abcdef123456"));
        String rewrapped = jdbcTemplate.queryForObject(
                "SELECT api_key_cipher FROM ai_config WHERE id = ?", String.class, vo.get("id"));
        assertThat(rewrapped).isNotEqualTo(cipher);
        assertThat(com.blog.module.ai.crypto.AesGcmUtil.decrypt(rewrapped,
                java.util.HexFormat.of().parseHex(TEST_MASTER_HEX)))
                .isEqualTo("sk-TEST-FAKE-abcdef123456");

        // 非法 Base URL（file:// 配置面 SSRF 探索）→ 400
        assertThat((Integer) env("POST", "/api/admin/ai/configs", admin, Map.of(
                "name", name + "-x", "baseUrl", "file:///etc/passwd", "model", "m")).get("code"))
                .isEqualTo(400);
    }

    @Test
    @DisplayName("内置行保护：禁删除(4006)/禁改身份(4006)；调参与换Key放行；自定义重名4007")
    void builtinGuards() throws Exception {
        String admin = adminToken();
        // 种子内置行 id=1（DeepSeek-V4-Flash, is_builtin=1, 占位密文）
        Map<String, Object> del = env("DELETE", "/api/admin/ai/configs/1", admin, null);
        assertThat((Integer) del.get("code")).as("内置禁止删除: %s", del.get("message")).isEqualTo(4006);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ai_config WHERE id=1 AND deleted=0", Integer.class)).isEqualTo(1);

        Map<String, Object> rename = env("PUT", "/api/admin/ai/configs/1", admin, Map.of(
                "name", "改名了", "baseUrl", "https://api.deepseek.com", "model", "x"));
        assertThat((Integer) rename.get("code")).as("内置禁改身份: %s", rename.get("message")).isEqualTo(4006);

        // 允许：只调参（身份字段原样传回）
        Map<String, Object> tweak = env("PUT", "/api/admin/ai/configs/1", admin, Map.of(
                "name", "DeepSeek-V4-Flash", "baseUrl", "https://api.deepseek.com",
                "model", "deepseek-v4-flash", "temperature", 0.20, "maxTokens", 600));
        assertThat((Integer) tweak.get("code")).as("内置允许调参: %s", tweak.get("message")).isZero();
        assertThat(castMap(tweak.get("data")).get("temperature").toString()).startsWith("0.2");
        // 掩码仍是占位形态（没导入过 Key），keyImported=false 供前端黄标
        assertThat(castMap(tweak.get("data")).get("keyImported")).isEqualTo(false);

        // 自定义行与内置名撞车 → 4007
        assertThat((Integer) env("POST", "/api/admin/ai/configs", admin, Map.of(
                "name", "DeepSeek-V4-Flash", "baseUrl", "https://x.dev", "model", "m")).get("code"))
                .isEqualTo(4007);
    }

    @Test
    @DisplayName("唯一默认事务互斥：设新默认后全站仅1行 is_default=1，旧默认让位但行保留")
    void singleDefault() throws Exception {
        String admin = adminToken();
        Map<String, Object> vo = castMap(env("POST", "/api/admin/ai/configs", admin, Map.of(
                "name", "m6-def-" + (System.nanoTime() % 1_000_000_000_000L),
                "baseUrl", "https://stub.local", "model", "stub-m", "isDefault", true)).get("data"));
        long newId = ((Number) vo.get("id")).longValue();

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ai_config WHERE is_default=1 AND deleted=0", Integer.class))
                .as("设默认后仅一行").isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT id FROM ai_config WHERE is_default=1 AND deleted=0", Long.class)).isEqualTo(newId);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT is_default FROM ai_config WHERE id=1", Integer.class)).isEqualTo(0);

        // 停用行不许设默认（矛盾态拒绝）
        long id2 = ((Number) castMap(env("POST", "/api/admin/ai/configs", admin, Map.of(
                "name", "m6-off-" + (System.nanoTime() % 1_000_000_000_000L),
                "baseUrl", "https://stub.local", "model", "m", "enabled", 0)).get("data")).get("id")).longValue();
        assertThat((Integer) env("PATCH", "/api/admin/ai/configs/" + id2 + "/default", admin, null).get("code"))
                .isEqualTo(400);
    }

    @Test
    @DisplayName("RBAC闸门：USER 1005/匿名 1001 挡管理口；status 公开；USER 可用 summary")
    void rbacGates() throws Exception {
        String user = userToken();
        assertThat((Integer) env("GET", "/api/admin/ai/configs", user, null).get("code")).isEqualTo(1005);
        assertThat((Integer) env("GET", "/api/admin/ai/logs", user, null).get("code")).isEqualTo(1005);
        assertThat((Integer) env("GET", "/api/admin/ai/configs", null, null).get("code")).isEqualTo(1001);
        // status 公开可探（游客按钮置灰也靠它）
        Map<String, Object> status = env("GET", "/api/ai/status", null, null);
        assertThat((Integer) status.get("code")).isZero();
        Map<String, Object> data = castMap(status.get("data"));
        assertThat(data.get("enabled")).isEqualTo(true);
        assertThat(data.get("remainingToday")).as("游客无额度概念").isNull();
        // 登录用户的 status 带额度视图
        assertThat(castMap(env("GET", "/api/ai/status", user, null).get("data"))
                .get("remainingToday")).isNotNull();
        // summary 未登录 → 1001
        assertThat((Integer) summary(null, "t", "正文".repeat(30)).get("code")).isEqualTo(1001);
    }

    // ==================== ② 摘要管道 ====================

    @Test
    @DisplayName("摘要全链路：真实调用+Key注入+提示词装配；二调fromCache零成本；cache_hit两态记账")
    void summaryPipelineAndCache() throws Exception {
        String admin = adminToken();
        long configId = makeDefaultWithKey(admin);
        String user = userToken();
        stub.reset();
        stub.enqueue(LlmResponse.builder().content(goodSummary())
                .promptTokens(1802).completionTokens(64).build());

        String md = "# 统一响应体\n\n所有接口返回 **同一信封** {code,message,data}……正文若干。";
        Map<String, Object> first = summary(user, "Spring Boot 3 实战", md);
        assertThat((Integer) first.get("code")).as("首调成功: %s", first.get("message")).isZero();
        Map<String, Object> d1 = castMap(first.get("data"));
        assertThat((Boolean) d1.get("fromCache")).isFalse();
        assertThat((String) d1.get("summary")).contains("统一响应体");
        assertThat(d1.get("fallback")).isEqualTo(false);
        assertThat(((Number) castMap(d1.get("tokensUsed")).get("prompt")).intValue()).isEqualTo(1802);
        assertThat(stub.callCount()).isEqualTo(1);

        // 管道中间产物：Key 解密注入 Bearer 请求；system 段=内置摘要模板角色句；user 段含标题
        LlmRequest sent = stub.lastRequest();
        assertThat(sent.getApiKey()).isEqualTo("sk-TEST-FAKE-000111222333");
        assertThat(sent.getBaseUrl()).isEqualTo("https://stub.local");
        assertThat(sent.getMessages()).hasSize(2);
        assertThat(sent.getMessages().get(0).getContent()).startsWith("你是一名中文技术博客编辑");
        assertThat(sent.getMessages().get(1).getContent()).contains("【文章标题】Spring Boot 3 实战");

        // 第二次同内容：命中缓存，不再打上游
        Map<String, Object> second = summary(user, "Spring Boot 3 实战", md);
        Map<String, Object> d2 = castMap(second.get("data"));
        assertThat((Boolean) d2.get("fromCache")).isTrue();
        assertThat((String) d2.get("summary")).isEqualTo((String) d1.get("summary"));
        assertThat(d2.get("tokensUsed")).as("命中无真实 usage").isNull();
        assertThat(stub.callCount()).as("缓存命中不发真实调用").isEqualTo(1);

        // 改动一个字符 → 哈希变化 → 绕开缓存（§6.1"任何实质改动改变哈希"）
        stub.enqueue(LlmResponse.builder().content(goodSummary()).build());
        summary(user, "Spring Boot 3 实战", md + "补一个字。");
        assertThat(stub.callCount()).isEqualTo(2);

        // 记账核对（本轮自造 configId 过滤，绕开共享库噪声）
        List<Map<String, Object>> logs = jdbcTemplate.queryForList(
                "SELECT cache_hit, status, prompt_tokens, cost_est FROM ai_call_log WHERE ai_config_id=?", configId);
        assertThat(logs).as("真实2次+缓存1次").hasSize(3);
        assertThat(logs.stream().filter(l -> ((Number) l.get("cache_hit")).intValue() == 1).count())
                .isEqualTo(1);
        Map<String, Object> real = logs.stream()
                .filter(l -> ((Number) l.get("cache_hit")).intValue() == 0
                        && l.get("prompt_tokens") != null).findFirst().orElseThrow();
        assertThat(((Number) real.get("prompt_tokens")).intValue()).isEqualTo(1802);
        Map<String, Object> cached = logs.stream()
                .filter(l -> ((Number) l.get("cache_hit")).intValue() == 1).findFirst().orElseThrow();
        assertThat(cached.get("prompt_tokens")).isNull();
        assertThat(((Number) cached.get("cost_est")).doubleValue()).isZero();
        assertThat(((Number) cached.get("status")).intValue()).isEqualTo(1);
    }

    @Test
    @DisplayName("降级矩阵：占位Key→4001；总开关关→4004；上游打穿→4002且失败记账status=2；配额→4003")
    void degradeMatrix() throws Exception {
        String user = userToken();
        stub.reset();

        // a) 当前默认=种子 DeepSeek（占位密文）→ 4001"尚未导入"
        Map<String, Object> pending = summary(user, "甲标题", "正文内容".repeat(30));
        assertThat((Integer) pending.get("code")).as("占位密文视为未配置: %s", pending.get("message")).isEqualTo(4001);

        // b) 造正常默认后关总开关 → 4004（开关检查在取配置之前，fail-fast）
        //    sys_config 写入必须走 MP Mapper：整个测试事务共享同一 SqlSession，
        //    一级缓存(SESSION 级)缓存 ai_enabled 查询结果，JdbcTemplate 的 UPDATE
        //    绕过 MyBatis 不触发 flushCache → 后续读会吃到旧值（M6 实测踩坑）
        makeDefaultWithKey(adminToken());
        setSysConfig("ai_enabled", "false");
        assertThat((Integer) summary(user, "乙标题", "另一正文".repeat(30)).get("code")).isEqualTo(4004);
        Map<String, Object> st = castMap(env("GET", "/api/ai/status", user, null).get("data"));
        assertThat(st.get("enabled")).as("status 如实上报关闭态").isEqualTo(false);
        setSysConfig("ai_enabled", "true");

        // c) 上游网络失败（重试耗尽）→ 4002 + status=2 失败日志
        stub.failWith(new LlmCallException("connect timed out", 0, true));
        long cfg = jdbcTemplate.queryForObject(
                "SELECT id FROM ai_config WHERE is_default=1 AND deleted=0", Long.class);
        assertThat((Integer) summary(user, "丙标题", "第三正文".repeat(30)).get("code"))
                .isEqualTo(4002);
        Map<String, Object> failLog = jdbcTemplate.queryForMap(
                "SELECT status, retry_count, error_msg FROM ai_call_log "
                        + "WHERE ai_config_id=? AND biz_type='SUMMARY' AND status=2", cfg);
        assertThat(((Number) failLog.get("retry_count")).intValue()).isEqualTo(2);
        assertThat((String) failLog.get("error_msg")).contains("connect timed out");

        // d) 配额闸门（确定性版）：日配额临时改 3、清掉内存 Redis 计数键，
        //    3 次真实调用后第 4 次新内容必 4003；而"已缓存内容"在额度耗尽后
        //    仍可命中（缓存检查在配额检查之前——命中不花钱所以不拦，§3.4 口径）
        setSysConfig("ai_daily_quota", "3");
        redis.delete("ai:quota:1:" + java.time.LocalDate.now().format(
                java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd")));
        stub.failWith(null);
        stub.reset();
        for (int i = 1; i <= 3; i++) {
            assertThat((Integer) summary(user, "配额戊", "配额正文".repeat(25) + i).get("code"))
                    .as("第 %d 次真实调用应在 3 次配额内", i).isZero();
        }
        assertThat((Integer) summary(user, "配额戊", "全新内容" + System.nanoTime()).get("code"))
                .as("额度耗尽后新内容 4003").isEqualTo(4003);
        Map<String, Object> afterExhaust = summary(user, "配额戊", "配额正文".repeat(25) + 2);
        assertThat((Integer) afterExhaust.get("code")).as("命中缓存不受配额限制").isZero();
        assertThat((Boolean) castMap(afterExhaust.get("data")).get("fromCache")).isTrue();
    }

    @Test
    @DisplayName("模型拒答/输出过短→机械兜底fallback:true仍算成功（§5.1 兜底链可演示）")
    void mechanicalFallback() throws Exception {
        makeDefaultWithKey(adminToken());
        String user = userToken();
        stub.reset();
        stub.enqueue(LlmResponse.builder().content("抱歉，我无法完成。")
                .promptTokens(100).completionTokens(5).build());
        String md = "开头一句很关键。".repeat(20) + "\n结尾也有信息。".repeat(10);
        Map<String, Object> envelope = summary(user, "兜底用例", md);
        assertThat((Integer) envelope.get("code")).as("兜底仍算成功").isZero();
        Map<String, Object> data = castMap(envelope.get("data"));
        assertThat((Boolean) data.get("fallback")).isTrue();
        assertThat((String) data.get("summary")).startsWith("开头一句很关键").endsWith("…");
    }

    // ==================== ③ 连通测试与日志 ====================

    @Test
    @DisplayName("连通测试：ok/4101/4102 三映射；CONNECT_TEST 全量记账；日志分页可按类型过滤")
    void connectTestAndLogs() throws Exception {
        String admin = adminToken();
        long id = makeDefaultWithKey(admin);
        stub.reset();
        stub.enqueue(LlmResponse.builder().content("OK").promptTokens(6).completionTokens(1).build());
        Map<String, Object> ok = env("POST", "/api/admin/ai/configs/" + id + "/test", admin, null);
        assertThat((Integer) ok.get("code")).isZero();
        assertThat((Boolean) castMap(ok.get("data")).get("ok")).isTrue();
        assertThat((String) castMap(ok.get("data")).get("reply")).isEqualTo("OK");
        // 测试请求形态：单条 user 消息 + max_tokens=8（§2.2 步骤 3 最小成本）
        assertThat(stub.lastRequest().getMessages()).hasSize(1);
        assertThat(stub.lastRequest().getMaxTokens()).isEqualTo(8);

        stub.failWith(new LlmCallException("上游返回 HTTP 401", 401, false));
        assertThat((Integer) env("POST", "/api/admin/ai/configs/" + id + "/test", admin, null).get("code"))
                .isEqualTo(4101);

        stub.failWith(new LlmCallException("网络调用异常: ConnectException", 0, true));
        assertThat((Integer) env("POST", "/api/admin/ai/configs/" + id + "/test", admin, null).get("code"))
                .isEqualTo(4102);

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ai_call_log WHERE biz_type='CONNECT_TEST' AND ai_config_id=?",
                Integer.class, id)).as("成功1+失败2 全量记账").isEqualTo(3);

        // 日志分页（ai:log 口）：按自造 configId 之外的通用过滤也验一把信封结构
        Map<String, Object> logs = env("GET", "/api/admin/ai/logs?pageNum=1&pageSize=5&bizType=CONNECT_TEST",
                admin, null);
        assertThat((Integer) logs.get("code")).isZero();
        Map<String, Object> page = castMap(logs.get("data"));
        assertThat(page).containsKeys("total", "pages", "pageNum", "pageSize", "list");
        List<Map<String, Object>> list = (List<Map<String, Object>>) page.get("list");
        assertThat(list).isNotEmpty();
        assertThat(list.get(0).get("bizType")).isEqualTo("CONNECT_TEST");
        assertThat(list.get(0)).containsKey("nickname");
    }
}
