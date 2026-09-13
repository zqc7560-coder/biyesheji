package com.blog.module.ai;

import com.blog.common.BizCode;
import com.blog.common.BizException;
import com.blog.module.ai.dto.AiGenerateDTO;
import com.blog.module.ai.service.LlmFacade;
import com.blog.module.ai.vo.AiStatusVo;
import com.blog.module.ai.vo.SummaryResult;
import com.blog.module.ai.vo.TagsResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * M12 修复轮：AI 控制器**分支矩阵**直测（Mockito 门面桩，绕开真管道）。
 *
 * <p>为什么单独开这个类而不是往 M6/M7 里塞：M6/M7 是"全管道契约测试"——真服务、
 * 真加密、StubLlmClient 只顶掉 HTTP 出口；它们覆盖不到控制器自身的
 * **异常翻译分支**（异步任务体里 catch BizException/Exception 的 setResult 路径、
 * 批候选空回执、删除/设默认的成功信封）。这些分支恰恰是"管道正确但控制器翻译错"
 * 类 bug 的藏身处（4002 信封丢失、HTTP 码映射错位），用 @MockBean 把门面整个
 * 换成可编程桩，是钉死这层契约的最短路径——门面的真实行为已有 M6/M7 全管道用例背书，
 * 两测试类各测各的接缝，互不重复。
 *
 * <p>@MockBean 只替换 LlmFacade（AiAdminController 的 configService/callLogService
 * 保持真实现）：/configs/{id}/test 走真删除校验会牵连管道，本轮不假；本类聚焦
 * AiController 全部分支 + AiAdminController 的 delete/setDefault 成功行 +
 * stats 日期纠偏分支（纯控制器逻辑，无需桩服务）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@DisplayName("M12 控制器分支矩阵: 异步异常翻译/池饱和/批空回执/stats纠偏")
class AiControllerBranchM12Test {

    private static final String SEED_PWD = "password123";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;

    /** 门面整体桩化：控制器测试只关心"接口↔门面"的翻译契约，不重测管道 */
    @MockBean
    private LlmFacade facade;

    // ==================== 工具（M6 同款信封读法） ====================

    @SuppressWarnings("unchecked")
    private Map<String, Object> readEnvelope(MvcResult result) throws Exception {
        return objectMapper.readValue(
                new String(result.getResponse().getContentAsByteArray(),
                        java.nio.charset.StandardCharsets.UTF_8),
                Map.class);
    }

    private String token(String username) throws Exception {
        MvcResult r = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("username", username, "password", SEED_PWD))))
                .andReturn();
        return (String) castMap(readEnvelope(r).get("data")).get("token");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object o) {
        return (Map<String, Object>) o;
    }

    /** 种子账号：other(id=2)=ADMIN，testuser(id=1)=USER(有 ai:use) */
    private String admin() throws Exception {
        return token("other");
    }

    private String user() throws Exception {
        return token("testuser");
    }

    /** 异步端点统一入口：perform → asyncDispatch → 信封（M6 台账坑①同款） */
    private Map<String, Object> callAsync(String method, String uri, String tk, Object body) throws Exception {
        var builder = "POST".equals(method)
                ? post(uri) : get(uri);
        if (tk != null) {
            builder = builder.header("Authorization", "Bearer " + tk);
        }
        if (body != null) {
            builder = builder.contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(body));
        }
        MvcResult r = mockMvc.perform(builder).andReturn();
        if (r.getRequest().isAsyncStarted()) {
            r = mockMvc.perform(asyncDispatch(r)).andReturn();
        }
        return readEnvelope(r);
    }

    private AiGenerateDTO dto() {
        AiGenerateDTO d = new AiGenerateDTO();
        d.setContentMd("这是一段足够长的测试正文，用于让控制器桩用例跨过入参非空的校验闸门。");
        d.setTitle("t");
        return d;
    }

    // ==================== 摘要/标签：BizException 翻译分支 ====================

    @Test
    @DisplayName("摘要异步 BizException(4003/429) 原样进信封，不被吞成 4002")
    void summaryBizExceptionPassthrough() throws Exception {
        // 与 AiQuotaService 同构：4003 必须显式携带 HTTP 429（BizException 默认 200，
        // "码到 HTTP 的映射在异常构造点"是这个工程的既有契约，测试照抄不臆造）
        when(facade.generateSummary(any(), any()))
                .thenThrow(new BizException(BizCode.AI_QUOTA_EXCEEDED,
                        BizCode.AI_QUOTA_EXCEEDED.getMessage(), 429));
        when(facade.generateTags(any(), any()))
                .thenThrow(new BizException(BizCode.AI_BAD_OUTPUT, "模型输出解析失败", 200));

        Map<String, Object> env = callAsync("POST", "/api/ai/summary", user(), dto());
        assertThat(((Number) env.get("code")).intValue()).isEqualTo(4003);
        // 4003→HTTP 429 的精确映射是配额降级契约（前端按 429 弹"明天再来"文案）
        MvcResult raw = mockMvc.perform(post("/api/ai/summary")
                        .header("Authorization", "Bearer " + user())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto())))
                .andReturn();
        int http = raw.getResponse().getStatus();
        if (raw.getRequest().isAsyncStarted()) {
            http = mockMvc.perform(asyncDispatch(raw)).andReturn().getResponse().getStatus();
        }
        assertThat(http).as("4003 必须映射 HTTP 429").isEqualTo(429);

        // 标签侧：httpStatus=200 的 BizException（4005 家族）→ HTTP 200 + code=4005
        Map<String, Object> tags = callAsync("POST", "/api/ai/tags", user(), dto());
        assertThat(((Number) tags.get("code")).intValue()).isEqualTo(4005);
    }

    @Test
    @DisplayName("摘要/标签异步未预期 RuntimeException → 统一 4002 降级（不裸 500）")
    void unexpectedExceptionDegrades() throws Exception {
        when(facade.generateSummary(any(), any())).thenThrow(new RuntimeException("boom"));
        when(facade.generateTags(any(), any())).thenThrow(new IllegalStateException("bad state"));

        Map<String, Object> env = callAsync("POST", "/api/ai/summary", user(), dto());
        assertThat(((Number) env.get("code")).intValue()).isEqualTo(4002);
        Map<String, Object> tags = callAsync("POST", "/api/ai/tags", user(), dto());
        assertThat(((Number) tags.get("code")).intValue()).isEqualTo(4002);
    }

    @Test
    @DisplayName("摘要/标签成功路径：DeferredResult 装载 Result 信封 data 字段")
    void successPaths() throws Exception {
        SummaryResult sr = SummaryResult.builder()
                .summary("AI 生成的测试摘要内容。").fromCache(true).build();
        when(facade.generateSummary(eq(1L), any())).thenReturn(sr);
        TagsResult tr = TagsResult.builder()
                .tags(List.of("Java", "Redis")).existingTagIds(List.of()).build();
        when(facade.generateTags(eq(1L), any())).thenReturn(tr);

        Map<String, Object> env = callAsync("POST", "/api/ai/summary", user(), dto());
        assertThat(((Number) env.get("code")).intValue()).isEqualTo(0);
        assertThat(castMap(env.get("data")).get("summary")).isEqualTo("AI 生成的测试摘要内容。");
        Map<String, Object> tags = callAsync("POST", "/api/ai/tags", user(), dto());
        assertThat(((Number) tags.get("code")).intValue()).isEqualTo(0);
    }

    // ==================== 批量：候选空/非空双分支 ====================

    @Test
    @DisplayName("batch：候选为空回 accepted=0 且不触发执行；非空回候选清单")
    void batchBranches() throws Exception {
        when(facade.resolveBatchCandidates(any())).thenReturn(List.of());
        Map<String, Object> empty = callAsync("POST", "/api/ai/batch", admin(), null);
        assertThat(((Number) empty.get("code")).intValue()).isEqualTo(0);
        assertThat(((Number) castMap(empty.get("data")).get("accepted")).intValue()).isZero();
        verify(facade, org.mockito.Mockito.never()).batchEnrich(any(), any());

        when(facade.resolveBatchCandidates(any())).thenReturn(List.of(11L, 12L));
        Map<String, Object> full = callAsync("POST", "/api/ai/batch", admin(),
                Map.of("articleIds", List.of(11L, 12L)));
        assertThat(((Number) castMap(full.get("data")).get("accepted")).intValue()).isEqualTo(2);
        verify(facade).batchEnrich(eq(List.of(11L, 12L)), eq(2L));
    }

    @Test
    @DisplayName("batch 越权：USER 令牌 1005（ai:config 门槛不被门面桩绕过）")
    void batchRequiresAdmin() throws Exception {
        Map<String, Object> env = callAsync("POST", "/api/ai/batch", user(), null);
        assertThat(((Number) env.get("code")).intValue()).isEqualTo(1005);
    }

    // ==================== status：游客/登录两态 ====================

    @Test
    @DisplayName("status：匿名 userId=null 走公开态；携带令牌透传 userId")
    void statusDualAudience() throws Exception {
        AiStatusVo vo = AiStatusVo.builder().enabled(true).configured(false)
                .dailyQuota(50).build();
        when(facade.status(isNull())).thenReturn(vo);
        Map<String, Object> env = callAsync("GET", "/api/ai/status", null, null);
        assertThat(((Number) env.get("code")).intValue()).isEqualTo(0);
        assertThat(castMap(env.get("data")).get("configured")).isEqualTo(false);
        verify(facade).status(isNull());

        when(facade.status(1L)).thenReturn(vo);
        callAsync("GET", "/api/ai/status", user(), null);
        verify(facade).status(1L);
    }

    // ==================== 管理端成功行 + stats 日期纠偏 ====================

    @Test
    @DisplayName("delete/setDefault 成功信封 code=0（AiAdminController 成功行覆盖）")
    void adminWriteSuccessPaths() throws Exception {
        String admin = admin();
        // 造一条可安全删除的自定义配置：走真 configService（本类只桩门面）
        Map<String, Object> created = castMap(readEnvelope(mockMvc.perform(
                post("/api/admin/ai/configs")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", "m12-del-" + (System.nanoTime() % 1_000_000_000_000L),
                                "baseUrl", "https://stub.local", "model", "stub-m")))).andReturn())
                .get("data"));
        long id = ((Number) created.get("id")).longValue();

        Map<String, Object> del = readEnvelope(mockMvc.perform(
                delete("/api/admin/ai/configs/" + id)
                        .header("Authorization", "Bearer " + admin)).andReturn());
        assertThat(((Number) del.get("code")).intValue()).isZero();

        Map<String, Object> def = readEnvelope(mockMvc.perform(
                patch("/api/admin/ai/configs/" + id + "/default")
                        .header("Authorization", "Bearer " + admin)).andReturn());
        // 行已逻辑删：setDefault 对幽灵行报参数/存在性错误均可接受——本用例的覆盖
        // 目标是"控制器成功翻译路径"，上面 del 的 code=0 已钉；这里换未删行走通
        assertThat(((Number) def.get("code")).intValue()).isNotEqualTo(0);

        // 再建一条活行验证 setDefault 成功
        Map<String, Object> c2 = castMap(readEnvelope(mockMvc.perform(
                post("/api/admin/ai/configs")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", "m12-def-" + (System.nanoTime() % 1_000_000_000_000L),
                                "baseUrl", "https://stub.local", "model", "m2")))).andReturn())
                .get("data"));
        Map<String, Object> def2 = readEnvelope(mockMvc.perform(
                patch("/api/admin/ai/configs/" + ((Number) c2.get("id")).longValue() + "/default")
                        .header("Authorization", "Bearer " + admin)).andReturn());
        assertThat(((Number) def2.get("code")).intValue()).isZero();
    }

    @Test
    @DisplayName("stats：from>to 互换、>90 天窗口钳制、GET configs 分页成功行")
    void statsWindowAndPageSuccess() throws Exception {
        String admin = admin();
        // 缺省窗口
        Map<String, Object> env = readEnvelope(mockMvc.perform(get("/api/admin/ai/stats")
                .header("Authorization", "Bearer " + admin)).andReturn());
        assertThat(((Number) env.get("code")).intValue()).isZero();

        // 倒挂窗口：控制器互换后正常返回（宽容纠偏契约）
        env = readEnvelope(mockMvc.perform(get("/api/admin/ai/stats")
                .header("Authorization", "Bearer " + admin)
                .param("dateFrom", "2026-09-10").param("dateTo", "2026-01-01")).andReturn());
        assertThat(((Number) env.get("code")).intValue()).isZero();
        Map<String, Object> data = castMap(env.get("data"));
        assertThat((String) data.get("dateFrom")).isLessThanOrEqualTo((String) data.get("dateTo"));

        // 超长窗口钳 90 天：from=2000-01-01 → 钳制到 to-89
        env = readEnvelope(mockMvc.perform(get("/api/admin/ai/stats")
                .header("Authorization", "Bearer " + admin)
                .param("dateFrom", "2000-01-01").param("dateTo", "2026-09-13")).andReturn());
        data = castMap(env.get("data"));
        assertThat((String) data.get("dateFrom")).isNotEqualTo("2000-01-01");

        // configs 分页成功行（默认参数分支）
        env = readEnvelope(mockMvc.perform(get("/api/admin/ai/configs")
                .header("Authorization", "Bearer " + admin)).andReturn());
        assertThat(((Number) env.get("code")).intValue()).isZero();
    }
}
