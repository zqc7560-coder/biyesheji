package com.blog.module.system;

import com.blog.module.ai.entity.AiCallLog;
import com.blog.module.ai.mapper.AiCallLogMapper;
import com.blog.support.InMemoryRedis;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * M8 系统配置 + 仪表盘接口级测试（API 文档 §8 系统配置与存储模块）。
 *
 * <p>测试重点不是"能不能改值"（那是 CRUD），而是三条治理规则：
 * ① 公开接口白名单——敏感运营键（ai_daily_quota/ai_price_*）绝不从匿名口出；
 * ② 可编辑白名单 + 类型校验——脏值整批拒绝且零部分生效（事务原子性）；
 * ③ RBAC 三态——匿名 401 / 普通用户 403（缺 config:manage）/ ADMIN 200。
 *
 * <p>基建沿用既有约定：InMemoryRedis @Primary、类级 @Transactional 回滚、
 * readEnvelope 显式 UTF-8。身份物料 testuser(ROLE_USER)/other(ROLE_ADMIN，种子 id=2)。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@DisplayName("M8 系统配置/仪表盘: 公开白名单/批量校验/RBAC/聚合契约")
class SystemConfigApiM8Test {

    private static final String SEED_PWD = "password123";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    /** MP Mapper 而非 JdbcTemplate 做前置写入：同 SqlSession 写会刷一级缓存（见用例注释） */
    @Autowired
    private AiCallLogMapper aiCallLogMapper;

    @TestConfiguration
    static class FakeRedisConfig {
        @Bean
        @Primary
        StringRedisTemplate inMemoryRedisTemplate() {
            return InMemoryRedis.template();
        }
    }

    // ==================== 工具 ====================

    @SuppressWarnings("unchecked")
    private Map<String, Object> castMap(Object o) {
        return (Map<String, Object>) o;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readEnvelope(MvcResult result) throws Exception {
        return objectMapper.readValue(
                new String(result.getResponse().getContentAsByteArray(),
                        java.nio.charset.StandardCharsets.UTF_8),
                Map.class);
    }

    private String loginForToken(String username) throws Exception {
        MvcResult r = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("username", username, "password", SEED_PWD))))
                .andReturn();
        return (String) castMap(readEnvelope(r).get("data")).get("token");
    }

    // ==================== 公开配置 ====================

    @Test
    @DisplayName("GET /api/system/config 匿名可达，且只出白名单键（ai_daily_quota/价格键必须缺席）")
    void publicConfigWhitelist() throws Exception {
        Map<String, Object> env = readEnvelope(mockMvc.perform(get("/api/system/config"))
                .andExpect(status().isOk()).andReturn());
        assertThat((Integer) env.get("code")).isZero();
        Map<String, Object> data = castMap(env.get("data"));

        // 门面键在位（种子值）
        assertThat(data.get("site_name")).isEqualTo("我的博客");
        assertThat(data).containsKeys("site_logo", "site_icp", "allow_register", "comment_audit");

        // 红线：运营/成本参数绝不随匿名接口出——爆破者拿到 quota/价格就有了薅库地图
        assertThat(data).doesNotContainKeys(
                "ai_daily_quota", "ai_enabled",
                "ai_price_per_1k_prompt", "ai_price_per_1k_completion");
    }

    // ==================== 管理端清单/修改 ====================

    @Test
    @DisplayName("清单接口 RBAC：匿名401 / 普通用户403 / ADMIN 200 且含 type+description 元数据")
    void adminListRbac() throws Exception {
        mockMvc.perform(get("/api/admin/system/config"))
                .andExpect(status().isUnauthorized());

        String userToken = loginForToken("testuser");     // ROLE_USER 无 config:manage
        mockMvc.perform(get("/api/admin/system/config")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(1005));

        String adminToken = loginForToken("other");
        Map<String, Object> env = readEnvelope(mockMvc.perform(
                get("/api/admin/system/config")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk()).andReturn());
        List<Map<String, Object>> items = (List<Map<String, Object>>) env.get("data");
        // 可编辑 7 键全在清单（前端动态表单的完整性——少一键就少一处治理入口）
        assertThat(items).extracting(i -> (String) i.get("key")).containsExactlyInAnyOrder(
                "site_name", "site_logo", "site_icp", "allow_register",
                "comment_audit", "ai_enabled", "ai_daily_quota");
        assertThat(items.get(0)).containsKeys("type", "description");
    }

    @Test
    @DisplayName("批量修改：成功路径落库+updatedBy 审计 + 响应回传新清单")
    void updateHappyPath() throws Exception {
        String adminToken = loginForToken("other");
        // 审计断言用种子管理员 id=2（other），不依赖 AdminBootstrapRunner 是否已在本上下文跑过

        Map<String, Object> env = readEnvelope(mockMvc.perform(
                put("/api/admin/system/config")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("items", List.of(
                                Map.of("key", "site_name", "value", "Qwen 的后花园"),
                                Map.of("key", "comment_audit", "value", "true"))))))
                .andExpect(status().isOk()).andReturn());
        assertThat((Integer) env.get("code")).isZero();

        // 库内事实（不是响应回显）：值与审计列同事务落定
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "select config_value, updated_by from sys_config where config_key='site_name'");
        assertThat(row.get("config_value")).isEqualTo("Qwen 的后花园");
        assertThat(((Number) row.get("updated_by")).longValue()).isEqualTo(2L); // 审计追责来自令牌非请求体

        // 公开接口即刻可见新值（无缓存层，读库即真相；M9 接镜像后此断言迁移为"双删后读"）
        Map<String, Object> pub = castMap(readEnvelope(mockMvc.perform(get("/api/system/config"))
                .andReturn()).get("data"));
        assertThat(pub.get("site_name")).isEqualTo("Qwen 的后花园");
    }

    @Test
    @DisplayName("批量修改防御矩阵：不可编辑键5001 / boolean脏值400 / 键不存在5001 / 重复键400 / 失败零部分生效")
    void updateDefenseMatrix() throws Exception {
        String adminToken = loginForToken("other");

        // ① ai_price 键存在但不在 EDITABLE_KEYS：与"不存在"同报 5001（不透露键真实存在性）
        Map<String, Object> e1 = readEnvelope(mockMvc.perform(
                put("/api/admin/system/config")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("items", List.of(
                                Map.of("key", "ai_price_per_1k_prompt", "value", "0.01"))))))
                .andReturn());
        assertThat((Integer) e1.get("code")).isEqualTo(5001);

        // ② boolean 键塞字母串：类型校验拦在写入前
        assertThat((Integer) readEnvelope(mockMvc.perform(
                put("/api/admin/system/config")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("items", List.of(
                                Map.of("key", "allow_register", "value", "yes-pls"))))))
                .andReturn()).get("code")).isEqualTo(400);

        // ③ 幽灵键
        assertThat((Integer) readEnvelope(mockMvc.perform(
                put("/api/admin/system/config")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("items", List.of(
                                Map.of("key", "site_owner_dog", "value", "旺财"))))))
                .andReturn()).get("code")).isEqualTo(5001);

        // ④ 同键重复提交
        assertThat((Integer) readEnvelope(mockMvc.perform(
                put("/api/admin/system/config")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("items", List.of(
                                Map.of("key", "site_name", "value", "A"),
                                Map.of("key", "site_name", "value", "B"))))))
                .andReturn()).get("code")).isEqualTo(400);

        // ⑤ 原子性：第一条合法 + 第二条非法 → 整批回滚，合法那条也不许留痕
        readEnvelope(mockMvc.perform(
                put("/api/admin/system/config")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("items", List.of(
                                Map.of("key", "site_name", "value", "半提交事故"),
                                Map.of("key", "comment_audit", "value", "not-a-bool"))))))
                .andReturn());
        String siteName = jdbcTemplate.queryForObject(
                "select config_value from sys_config where config_key='site_name'", String.class);
        assertThat(siteName).isEqualTo("我的博客");   // 种子原值未动
    }

    @Test
    @DisplayName("注册开关联动：allow_register=false 后注册口 403（M2 开关消费链与 M8 修改链闭环）")
    void registerSwitchEndToEnd() throws Exception {
        String adminToken = loginForToken("other");
        readEnvelope(mockMvc.perform(
                put("/api/admin/system/config")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("items", List.of(
                                Map.of("key", "allow_register", "value", "false"))))))
                .andReturn());

        // 同请求线程上下文内注册必须被拒（读库开关，M2 AuthService.isRegisterOpen 消费）
        assertThat((Integer) readEnvelope(mockMvc.perform(
                post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", "m8shutuser", "password", "Abcd@1234",
                                "nickname", "开关样本", "email", "m8shut@blog.local"))))
                .andReturn()).get("code")).isEqualTo(1005);
    }

    // ==================== 仪表盘 ====================

    @Test
    @DisplayName("GET /api/admin/dashboard：RBAC 三态 + counts 契约 + 趋势稠密 7 点")
    void dashboardContract() throws Exception {
        String userToken = loginForToken("testuser");
        mockMvc.perform(get("/api/admin/dashboard")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden());

        String adminToken = loginForToken("other");
        Map<String, Object> env = readEnvelope(mockMvc.perform(
                get("/api/admin/dashboard")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk()).andReturn());
        Map<String, Object> data = castMap(env.get("data"));

        // 计数块字段齐备且非负（绝对值不做断言——共享 blog_test 库跨轮样本，台账坑①纪律）
        Map<String, Object> counts = castMap(data.get("counts"));
        assertThat(counts).containsKeys("articleTotal", "articlePublished", "userTotal",
                "userActive", "commentTotal", "commentPending", "aiCalls7d", "aiCost7d");
        assertThat(((Number) counts.get("userTotal")).longValue()).isGreaterThanOrEqualTo(3L); // 至少三个种子用户

        // 趋势序列必须稠密 7 点（折线图断线防御）且末位是今天
        List<Map<String, Object>> aiTrend = (List<Map<String, Object>>) data.get("aiTrend");
        assertThat(aiTrend).hasSize(7);
        assertThat(aiTrend.get(6).get("date"))
                .isEqualTo(java.time.LocalDate.now().toString());
        assertThat((List<Map<String, Object>>) data.get("pvTrend")).hasSize(7);
        assertThat((String) data.get("note")).contains("M9");   // 占位口径声明在契约里
    }

    @Test
    @DisplayName("仪表盘 AI 趋势反映真实日志行：插一条今日 ai_call_log 后 aiTrend 末位与 aiCalls7d 同步 +1")
    void dashboardAiTrendLive() throws Exception {
        // 前置数据必须走 MP Mapper 而不是 JdbcTemplate——M6 台账坑②同族再命中：
        // Spring 管理的事务内 SqlSession 复用，MyBatis 一级缓存只被"自己执行的写"
        // 冲刷，裸 JdbcTemplate 插入后 mapper 仍读到旧查询缓存（首轮实测 +1 断言拿不到）。
        String adminToken = loginForToken("other");
        Map<String, Object> before = castMap(readEnvelope(mockMvc.perform(
                get("/api/admin/dashboard").header("Authorization", "Bearer " + adminToken))
                .andReturn()).get("data"));
        long before7d = ((Number) castMap(before.get("counts")).get("aiCalls7d")).longValue();
        long beforeToday = ((Number) lastPoint(before).get("value")).longValue();

        AiCallLog probe = new AiCallLog();
        probe.setUserId(1L);
        probe.setBizType("SUMMARY");
        probe.setAiConfigId(1L);
        probe.setModel("stub-model");
        probe.setStatus(1);
        probe.setLatencyMs(100);
        probe.setCreatedAt(java.time.LocalDateTime.now());
        aiCallLogMapper.insert(probe);

        Map<String, Object> after = castMap(readEnvelope(mockMvc.perform(
                get("/api/admin/dashboard").header("Authorization", "Bearer " + adminToken))
                .andReturn()).get("data"));
        assertThat(((Number) castMap(after.get("counts")).get("aiCalls7d")).longValue())
                .isEqualTo(before7d + 1);
        assertThat(((Number) lastPoint(after).get("value")).longValue())
                .isEqualTo(beforeToday + 1);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> lastPoint(Map<String, Object> data) {
        List<Map<String, Object>> trend = (List<Map<String, Object>>) data.get("aiTrend");
        return trend.get(trend.size() - 1);
    }
}
