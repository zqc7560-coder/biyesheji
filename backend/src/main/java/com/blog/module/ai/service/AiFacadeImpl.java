package com.blog.module.ai.service;

import com.blog.common.BizCode;
import com.blog.common.BizException;
import com.blog.module.ai.client.LlmCallException;
import com.blog.module.ai.client.LlmClient;
import com.blog.module.ai.client.LlmRequest;
import com.blog.module.ai.client.LlmResponse;
import com.blog.module.ai.crypto.KeyCipherService;
import com.blog.module.ai.crypto.KeyDecryptException;
import com.blog.module.ai.dto.AiGenerateDTO;
import com.blog.module.ai.entity.AiConfig;
import com.blog.module.ai.enums.BizType;
import com.blog.module.ai.enums.PromptTemplate;
import com.blog.module.ai.vo.AiStatusVo;
import com.blog.module.ai.vo.SummaryResult;
import com.blog.module.ai.vo.TestResult;
import com.blog.module.article.service.MarkdownProcessor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * LlmFacade 实现：AI 调用管道的编排者（《AI模块设计.md》§3.1）。
 *
 * <p>本类只做"编排"——每一步都委托给职责单一的协作者（限流/缓存/解析/记账/客户端），
 * 因此每一步都能独立替换与单测；桩掉 {@link LlmClient} 即可在 H2 测试库里
 * 全链路跑通摘要管道而不出网（AiPipelineM6Test 的做法）。
 *
 * <p>降级矩阵（§3.5）在本类的映射：
 * <ul>
 *   <li>未配置默认 / Key 未导入（占位密文）/ 解密失败 → 4001</li>
 *   <li>重试耗尽 / 网络失败 / 上游 4xx 打穿 → 4002（管理端 test 例外：401→4101、不可达→4102）</li>
 *   <li>配额超限 → 4003（AiQuotaService 抛，这里不拦）</li>
 *   <li>总开关关 → 4004</li>
 *   <li>输出不可解析（摘要侧几乎不发生——有清洗与兜底；保留给标签侧）→ 4005</li>
 * </ul>
 * 注意所有分支都**写日志后**再抛业务异常：失败调用同样要进 ai_call_log（status=2），
 * 否则答辩统计"成功率"就没有分母里的失败者了。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiFacadeImpl implements LlmFacade {

    /** 截断策略（§5.1）：正文首 4000 字 + 尾 1000 字——长文首尾信息密度高，控输入 token 成本 */
    private static final int HEAD_CHARS = 4000;
    private static final int TAIL_CHARS = 1000;

    /** 连通性测试的系统提示：极简指令，max_tokens=8 足够回"OK"，单次成本 <0.001 元 */
    private static final String CONNECT_TEST_PROMPT = "请仅回复OK";

    private final AiConfigService configService;
    private final AiSysConfigReader sysConfig;
    private final AiQuotaService quotaService;
    private final AiCacheService cacheService;
    private final AiCallLogService callLogService;
    private final ResultParserService parser;
    private final KeyCipherService keyCipher;
    private final LlmClient llmClient;
    private final ObjectMapper objectMapper;

    // ==================== 摘要 ====================

    @Override
    public SummaryResult generateSummary(Long userId, AiGenerateDTO cmd) {
        assertEnabled();
        AiConfig config = requireDefaultConfig();

        // 1) 归一化输入：Markdown → 纯文本（复用 M3 MarkdownProcessor——写入与检索的
        //    content_text 与 AI 输入用同一实现，口径恒定一致），再截断控成本
        String plain = MarkdownProcessor.extractText(cmd.getContentMd());
        if (plain.isBlank()) {
            throw new BizException(BizCode.PARAM_ERROR, "正文没有可分析的文字内容");
        }
        String truncated = truncateForPrompt(plain);
        String hash = AiCacheService.contentHash(cmd.getTitle(), cmd.getContentMd());

        // 2) 缓存查找（命中：不耗配额、不发真实调用、cache_hit=1 留痕）
        String promptVer = summaryPromptVersion(config);
        String cacheKey = cacheService.buildKey(BizType.SUMMARY, hash, config.getModel(),
                promptVer, config.getTemperature());
        String cachedJson = cacheService.get(cacheKey);
        if (cachedJson != null) {
            SummaryResult hit = fromCacheJson(cachedJson, config.getModel());
            if (hit != null && !hit.getSummary().isEmpty()) {
                callLogService.recordSuccess(userId, BizType.SUMMARY, config.getId(), config.getModel(),
                        null, null, 2, 0, true, hash);
                return hit;
            }
            // 载荷损坏（结构升级遗留）：当作未命中继续走真实调用，自愈而非把空摘要喂给用户
        }

        // 3) 配额闸门：真实调用前"检查+预扣"。预扣语义=失败也计数——客户端重试
        //    已经真实烧了上游配额（429/超时同样花钱），死后计数会留下"死 Key 无限刷"漏洞
        quotaService.assertWithinQuota(userId);
        quotaService.consume(userId);
        long start = System.nanoTime();
        LlmResponse resp;
        try {
            resp = callWithDecryptedKey(userId, BizType.SUMMARY, config,
                    PromptTemplate.SUMMARY, truncated, cmd.getTitle(), hash, start);
        } catch (BizException e) {
            throw e;
        }

        // 4) 解析与合法化（清洗 → 必要时机械兜底）
        long latency = (System.nanoTime() - start) / 1_000_000;
        String sanitized = parser.sanitizeSummary(resp.getContent());
        boolean fallback = parser.shouldFallback(sanitized);
        String finalText = fallback
                ? MarkdownProcessor.fallbackSummary(plain, 120)   // §5.1 兜底：正文前 120 字+省略号
                : sanitized;

        SummaryResult result = SummaryResult.builder()
                .summary(finalText)
                .fromCache(false)
                .fallback(fallback)
                .tokensUsed(new SummaryResult.Tokens(resp.getPromptTokens(), resp.getCompletionTokens()))
                .latencyMs(latency)
                .build();

        // 5) 成功后才写缓存（失败不缓存 → 下次可重试）；记账 status=1/3
        cacheService.put(cacheKey, result);
        callLogService.recordSuccess(userId, BizType.SUMMARY, config.getId(), config.getModel(),
                resp.getPromptTokens(), resp.getCompletionTokens(),
                latency, resp.getRetryCount(), false, hash);
        return result;
    }

    // ==================== 连通测试 ====================

    @Override
    public TestResult testConnection(Long configId, Long adminUserId) {
        AiConfig config = configService.mustExist(configId);
        long start = System.nanoTime();
        try {
            String apiKey = decryptKey(config);
            LlmRequest req = LlmRequest.builder()
                    .baseUrl(config.getBaseUrl())
                    .apiKey(apiKey)
                    .model(config.getModel())
                    // 最小成本探测：不套角色模板，单条 user 消息 + max_tokens=8（§2.2 步骤 3）
                    .messages(List.of(LlmRequest.Message.builder()
                            .role("user").content(CONNECT_TEST_PROMPT).build()))
                    .temperature(new BigDecimal("0.00"))
                    .maxTokens(8)
                    .timeoutMs(Math.min(config.getTimeoutMs() == null ? 30000 : config.getTimeoutMs(), 15000))
                    .extraBody(enableThinkingBody())
                    .build();
            LlmResponse resp = llmClient.chat(req);
            long latency = (System.nanoTime() - start) / 1_000_000;
            callLogService.recordSuccess(adminUserId, BizType.CONNECT_TEST, config.getId(),
                    config.getModel(), resp.getPromptTokens(), resp.getCompletionTokens(),
                    latency, resp.getRetryCount(), false, null);
            String reply = resp.getContent() == null ? "" :
                    resp.getContent().substring(0, Math.min(50, resp.getContent().length()));
            return TestResult.builder().ok(true).latencyMs(latency).reply(reply)
                    // 测试成本估算由日志层统一口径（这里回显给管理员"这次测试花了多少"）
                    .costEst(BigDecimal.ZERO)
                    .build();
        } catch (LlmCallException e) {
            long latency = (System.nanoTime() - start) / 1_000_000;
            callLogService.recordFailure(adminUserId, BizType.CONNECT_TEST, config.getId(),
                    config.getModel(), latency, e.isRetryExhausted() ? 2 : 0, e.getMessage(), null);
            // §2.2 步骤 3 的状态码精确映射（用户端统一 4002，管理端要"诊断报告"级精度）
            if (e.getHttpStatus() == 401 || e.getHttpStatus() == 403) {
                throw new BizException(BizCode.AI_KEY_INVALID,
                        "API Key 无效（上游返回 " + e.getHttpStatus() + "）");
            }
            if (e.getHttpStatus() == 0) {
                throw new BizException(BizCode.AI_BASE_URL_UNREACHABLE,
                        "Base URL 不可达：请检查端点拼写与服务器出网（" + e.getMessage() + "）");
            }
            throw new BizException(BizCode.AI_CALL_FAILED, "连通测试失败：HTTP " + e.getHttpStatus());
        } catch (KeyDecryptException e) {
            throw new BizException(BizCode.AI_NOT_CONFIGURED, "该配置尚未导入真实 API Key（密文仍是占位串）");
        }
    }

    // ==================== 状态探测 ====================

    @Override
    public AiStatusVo status(Long userId) {
        boolean enabled = sysConfig.isAiEnabled();
        AiConfig config = configService.getDefaultEnabled();
        boolean configured = enabled && config != null && !AiConfigService.pendingKey(config);
        Integer remaining = null;
        if (userId != null) {
            int quota = sysConfig.dailyQuota();
            remaining = (int) Math.max(0, quota - quotaService.current(userId));
        }
        return AiStatusVo.builder()
                .enabled(enabled)
                .configured(configured)
                .defaultModel(configured ? config.getModel() : null)
                .remainingToday(remaining)
                .dailyQuota(sysConfig.dailyQuota())
                .build();
    }

    // ==================== 管道零件 ====================

    /** 真实外呼 + 统一异常翻译（摘要/标签共用；M7 标签链路直接复用本方法） */
    private LlmResponse callWithDecryptedKey(Long userId, BizType bizType, AiConfig config,
                                             PromptTemplate template, String truncatedBody,
                                             String title, String hash, long pipelineStart) {
        // 提示词三级优先（§4）：ai_config 自定义 > 内置枚举。system 段固化角色与输出契约；
        // 模板版本进缓存键（自定义模板用内容摘要当版本——改一个字换一把键，绝不误命中）
        String override = bizType == BizType.SUMMARY ? config.getSummaryPrompt() : config.getTagPrompt();
        String systemText = (override == null || override.isBlank())
                ? template.systemPrompt : override;

        String apiKey;
        try {
            apiKey = decryptKey(config);
        } catch (KeyDecryptException e) {
            callLogService.recordFailure(userId, bizType, config.getId(), config.getModel(),
                    System.nanoTime() / 1_000_000 - pipelineStart, 0, e.getMessage(), hash);
            throw new BizException(BizCode.AI_NOT_CONFIGURED,
                    "默认模型尚未导入 API Key，请管理员在「AI 配置」页填写");
        }

        LlmRequest req = LlmRequest.builder()
                .baseUrl(config.getBaseUrl())
                .apiKey(apiKey)
                .model(config.getModel())
                .messages(List.of(
                        LlmRequest.Message.builder().role("system").content(systemText).build(),
                        LlmRequest.Message.builder().role("user")
                                .content(template.renderUser(title, truncatedBody)).build()))
                .temperature(config.getTemperature())
                .maxTokens(config.getMaxTokens())
                .timeoutMs(config.getTimeoutMs())
                .extraBody(enableThinkingBody())
                .build();
        try {
            return llmClient.chat(req);
        } catch (LlmCallException e) {
            long latency = (System.nanoTime() - pipelineStart) / 1_000_000;
            callLogService.recordFailure(userId, bizType, config.getId(), config.getModel(),
                    latency, e.isRetryExhausted() ? 2 : 0, e.getMessage(), hash);
            // 用户端不暴露厂商细节（401 还是超时对用户无意义），统一降级 4002（§3.5 矩阵）
            throw new BizException(BizCode.AI_CALL_FAILED);
        }
    }

    /** 解密默认配置的 Key（占位密文 → KeyDecryptException，由调用方翻译 4001） */
    private String decryptKey(AiConfig config) {
        if (AiConfigService.pendingKey(config)) {
            throw new KeyDecryptException("密文为占位串（未导入 Key）", null);
        }
        return keyCipher.decrypt(config.getApiKeyCipher());
    }

    /**
     * 深度思考模型闸门：DashScope 兼容模式下 qwen3 系默认开思考链，思维链会把
     * max_tokens 预算吃光导致 content 为空（实测契约）。统一关思考——摘要是
     * 压缩任务不是推理任务，关思考同时显著降延迟与费用。
     * OpenAI 规范允许忽略未知顶层参数，DeepSeek 端无副作用（差异数据化，代码不分叉）。
     */
    private Map<String, Object> enableThinkingBody() {
        return Map.of("enable_thinking", false);
    }

    /** 总开关（§3.5"功能被关"→4004）；status 接口不调它——status 要把"关了"如实报给前端 */
    private void assertEnabled() {
        if (!sysConfig.isAiEnabled()) {
            throw new BizException(BizCode.AI_DISABLED);
        }
    }

    /** 默认配置解析：不存在/被停用 → 4001（区分文案，管理员排障友好） */
    private AiConfig requireDefaultConfig() {
        AiConfig config = configService.getDefaultEnabled();
        if (config == null) {
            // 诊断细分：有没有"默认但被停用"的行？有则文案指向"启用它"，没有才是真没配
            boolean defaultButDisabled = configService.page(1, 100).getList().stream()
                    .anyMatch(v -> v.getIsDefault() != null && v.getIsDefault() == 1);
            throw new BizException(BizCode.AI_NOT_CONFIGURED,
                    defaultButDisabled ? "默认 AI 配置已停用，请管理员启用或更换默认"
                            : "管理员尚未设置默认 AI 配置");
        }
        return config;
    }

    /** 首尾截断（§5.1）：≤5000 字不截；超长取 首4000 + …… + 尾1000（按码点安全 substring：
     *  plain 已被 extractText 折叠为单行，仍防代理对截半的边角） */
    static String truncateForPrompt(String plain) {
        if (plain.length() <= HEAD_CHARS + TAIL_CHARS) {
            return plain;
        }
        return plain.substring(0, HEAD_CHARS) + "\n……（中段省略）……\n"
                + plain.substring(plain.length() - TAIL_CHARS);
    }

    /** 缓存键里的提示词版本段（§6.1 + 迭代规范 §5.3 的"内容即版本"实现） */
    private String summaryPromptVersion(AiConfig config) {
        String custom = config.getSummaryPrompt();
        if (custom == null || custom.isBlank()) {
            return PromptTemplate.SUMMARY.version;
        }
        // 管理员自定义模板：取模板内容前 8 位哈希作版本号——模板一改，键必变
        return "cfg-" + AiCacheService.contentHash(null, custom).substring(0, 8);
    }

    /** 缓存 JSON → SummaryResult（读时重建；fromCache 在此翻真） */
    private SummaryResult fromCacheJson(String json, String model) {
        try {
            JsonNode n = objectMapper.readTree(json);
            return SummaryResult.builder()
                    .summary(n.path("summary").asText(""))
                    .fromCache(true)
                    .fallback(n.path("fallback").asBoolean(false))
                    .tokensUsed(null)                 // 命中没有真实 usage
                    .latencyMs(2)                     // §6.2 口径：命中路径 latency≈2ms
                    .build();
        } catch (Exception e) {
            // 缓存内容坏掉（结构升级遗留等）：当作 miss 走真实调用，自愈而非报错
            log.warn("AI 缓存载荷解析失败（按未命中处理, model={}）: {}", model, e.getMessage());
            return SummaryResult.builder().summary("").fromCache(true).build();
        }
    }
}
