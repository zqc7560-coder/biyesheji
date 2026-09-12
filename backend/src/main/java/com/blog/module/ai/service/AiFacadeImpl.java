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
import com.blog.module.ai.vo.TagsResult;
import com.blog.module.ai.vo.TestResult;
import com.blog.module.article.service.MarkdownProcessor;
import com.blog.module.tag.entity.Tag;
import com.blog.module.tag.mapper.TagMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
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
    /**
     * M7：标签存在性回查（existingTagIds）。直接注入 TagMapper 而非 TagService：
     * 门面只需要"按名批量查"这一只读动作，TagService 的写侧方法（create/merge）
     * 挂在这里会诱导未来误改业务表——AI 模块对 tag 表的红线是**只读**（§5.2）。
     */
    private final TagMapper tagMapper;
    /**
     * M7 批量补全的编排者（独立成类的原因见 AiBatchService 注释：它是 AI 模块里
     * 唯一"写业务表"的入口，把红线集中在一个类里比散在门面里好审计）。
     * 门面→批处理是单向构造依赖（批处理回门面走 ObjectProvider 延迟解析，环在那一侧断开）。
     */
    private final AiBatchService batchService;

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

    // ==================== 标签（M7） ====================

    /**
     * POST /api/ai/tags —— 生成候选标签。
     *
     * <p>管道与摘要完全同构（开关→配置→归一化/哈希→缓存→配额→真实调用→解析→缓存→记账），
     * 唯一分叉在"解析失败"的处置：摘要可回退机械截取（丑但有用），标签不可
     * （截词当标签=往唯一键业务表灌垃圾），三级防御全失败 → 4005 让用户重试。
     */
    @Override
    public TagsResult generateTags(Long userId, AiGenerateDTO cmd) {
        assertEnabled();
        AiConfig config = requireDefaultConfig();

        String plain = MarkdownProcessor.extractText(cmd.getContentMd());
        if (plain.isBlank()) {
            throw new BizException(BizCode.PARAM_ERROR, "正文没有可分析的文字内容");
        }
        String truncated = truncateForPrompt(plain);
        String hash = AiCacheService.contentHash(cmd.getTitle(), cmd.getContentMd());

        // 缓存查找（与摘要共享同一 contentHash——§6.2"同一次归一化哈希"的兑现；
        // bizType 段不同所以两把键，互不污染）
        String cacheKey = cacheService.buildKey(BizType.TAGS, hash, config.getModel(),
                tagPromptVersion(config), config.getTemperature());
        String cachedJson = cacheService.get(cacheKey);
        if (cachedJson != null) {
            List<String> cachedTags = fromCacheTagsJson(cachedJson);
            if (cachedTags != null && !cachedTags.isEmpty()) {
                callLogService.recordSuccess(userId, BizType.TAGS, config.getId(), config.getModel(),
                        null, null, 2, 0, true, hash);
                return TagsResult.builder()
                        .tags(cachedTags)
                        .existingTagIds(lookupExistingTagIds(cachedTags))
                        .fromCache(true)
                        .tokensUsed(null)
                        .latencyMs(2)
                        .build();
            }
        }

        quotaService.assertWithinQuota(userId);
        quotaService.consume(userId);
        long start = System.nanoTime();
        LlmResponse resp;
        try {
            resp = callWithDecryptedKey(userId, BizType.TAGS, config,
                    PromptTemplate.TAGS, truncated, cmd.getTitle(), hash, start);
        } catch (BizException e) {
            throw e;
        }

        long latency = (System.nanoTime() - start) / 1_000_000;
        List<String> tags = parser.parseTags(resp.getContent());
        if (tags.isEmpty()) {
            // §3.5 矩阵"返回不可解析"行：原文截 500 字进 error_msg 备查（排障要看模型
            // 到底吐了什么才能迭代提示词），业务侧回 4005 建议重试
            String preview = resp.getContent() == null ? "" : resp.getContent();
            callLogService.recordFailure(userId, BizType.TAGS, config.getId(), config.getModel(),
                    latency, resp.getRetryCount(),
                    "输出不可解析: " + (preview.length() > 480 ? preview.substring(0, 480) : preview), hash);
            throw new BizException(BizCode.AI_BAD_OUTPUT,
                    "AI 返回的标签无法解析，请重试或手动填写标签");
        }

        TagsResult result = TagsResult.builder()
                .tags(tags)
                .existingTagIds(lookupExistingTagIds(tags))
                .fromCache(false)
                .tokensUsed(new SummaryResult.Tokens(resp.getPromptTokens(), resp.getCompletionTokens()))
                .latencyMs(latency)
                .build();
        cacheService.put(cacheKey, tags);   // 缓存载荷只存标签数组：与"existingTagIds 实时查"的易变态分离
        callLogService.recordSuccess(userId, BizType.TAGS, config.getId(), config.getModel(),
                resp.getPromptTokens(), resp.getCompletionTokens(),
                latency, resp.getRetryCount(), false, hash);
        return result;
    }

    /**
     * existingTagIds 实时回查：把候选标签映射到已入库标签的 id（§7 响应契约）。
     *
     * <p>实现取舍：一次全表拉取（tag 表毕设量级 &lt; 几百行）+ 小写键映射，
     * 而不是逐名 selectOne——① 候选最多 6 个但 N 次查询仍是 N 次往返；② MySQL 的
     * uk_name 走 utf8mb4_general_ci 大小写不敏感，H2（测试库）默认敏感——Java 侧
     * 统一 toLowerCase 比对让两库行为一致，不把大小写语义交给方言。
     */
    private List<Long> lookupExistingTagIds(List<String> tags) {
        if (tags.isEmpty()) {
            return List.of();
        }
        Map<String, Long> byLowerName = tagMapper.selectList(new LambdaQueryWrapper<Tag>()
                        .select(Tag::getId, Tag::getName))
                .stream()
                .collect(java.util.stream.Collectors.toMap(
                        t -> t.getName().toLowerCase(), Tag::getId, (a, b) -> a));
        List<Long> ids = new ArrayList<>();
        for (String tag : tags) {
            Long id = byLowerName.get(tag.toLowerCase());
            if (id != null) {
                ids.add(id);
            }
        }
        return ids;
    }

    /** 缓存 JSON → 标签数组（损坏载荷返回 null 按未命中处理，与摘要侧自愈策略一致） */
    private List<String> fromCacheTagsJson(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {
            });
        } catch (Exception e) {
            log.warn("AI 标签缓存载荷解析失败（按未命中处理）: {}", e.getMessage());
            return null;
        }
    }

    // ==================== 批量补全（M7） ====================

    /**
     * 批量补全候选解析（同步返回给 Controller 做响应回显；实际执行走异步）。
     * 语义细节见 AiBatchService.resolveCandidates。
     */
    @Override
    public List<Long> resolveBatchCandidates(List<Long> requestedArticleIds) {
        return batchService.resolveCandidates(requestedArticleIds);
    }

    /**
     * 批量补全（§3.1 门面声明 + §7.4 异步方案）。提交即返回，不阻塞调用方；
     * 逐篇失败只留痕跳过——批量治理的前提是"一坏不拖全批"。
     */
    @Override
    public void batchEnrich(List<Long> articleIds, Long adminUserId) {
        batchService.runAsync(articleIds, adminUserId);
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
     * 深度思考模型闸门：两家厂商对"关思考"的参数形态不同（真实端到端冒烟实测，2026-09 M7）：
     * - DashScope 兼容模式 qwen3 系：顶层 `enable_thinking:false`（M6 结论，有效）；
     * - DeepSeek deepseek-v4-flash：思考模型，**顶层 enable_thinking 被无提示忽略**，
     *   content 恒为空串、completion 全部是 reasoning_tokens（标签侧三级解析
     *   对着空串必吐 4005；摘要侧更隐蔽——落"正文前 120 字"机械兜底，用户以为
     *   是真摘要）。官方参数是 `thinking:{"type":"disabled"}`。
     * 两套参数**同时携带**：OpenAI 兼容协议允许忽略未知顶层参数（各厂商互无副作用），
     * 差异数据化进请求体，客户端与调用链仍是一套代码（§3.2 零代码换厂商的红线不破）。
     * 摘要是压缩任务不是推理任务，关思考同时显著降延迟与费用。
     */
    private Map<String, Object> enableThinkingBody() {
        return Map.of(
                "enable_thinking", false,                       // DashScope qwen3 形态（M6）
                "thinking", Map.of("type", "disabled"));        // DeepSeek V4 形态（M7 冒烟实测补）
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

    /** 缓存键里的摘要提示词版本段（§6.1 + 迭代规范 §5.3 的"内容即版本"实现） */
    private String summaryPromptVersion(AiConfig config) {
        return promptVersion(config.getSummaryPrompt(), PromptTemplate.SUMMARY);
    }

    /** 缓存键里的标签提示词版本段（M7）：与摘要同一套"内容即版本"策略 */
    private String tagPromptVersion(AiConfig config) {
        return promptVersion(config.getTagPrompt(), PromptTemplate.TAGS);
    }

    /**
     * 提示词版本号：内置模板用枚举 version（ai_tag_v1 等）；管理员自定义模板
     * 取其内容摘要前 8 位作版本——模板正文改一个字，缓存键必换；
     * 只改无关字段（如顺手保存表单）不会误杀缓存。
     */
    private static String promptVersion(String custom, PromptTemplate builtin) {
        if (custom == null || custom.isBlank()) {
            return builtin.version;
        }
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
