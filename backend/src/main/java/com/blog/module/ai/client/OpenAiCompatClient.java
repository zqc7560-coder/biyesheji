package com.blog.module.ai.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

/**
 * OpenAI 兼容协议统一客户端（《AI模块设计.md》§3.2）——课题"热切换模型"能力的落点。
 *
 * <p>DeepSeek（https://api.deepseek.com）与 DashScope 兼容模式
 * （https://dashscope.aliyuncs.com/compatible-mode/v1）请求/响应结构完全一致，
 * 只有 base_url/model/apiKey 三个参数不同 → 两家共用本类，零厂商分支代码。
 *
 * <p>HTTP 实现选型偏离说明：设计文档写的是 OkHttp 4，此处改用 JDK 内置
 * {@link java.net.http.HttpClient}。理由：① 需要的能力（连接池复用、按请求超时、
 * POST JSON）JDK 客户端全部具备，语义等价；② 少引入一个三方依赖 = 少一个供应链
 * 风险与离线构建障碍（本机 Maven 仓库在 E 盘，新增依赖需联网拉取）；③ 超时链约束
 * （客户端 30s &lt; DeferredResult 35s &lt; Axios 40s）不依赖具体 HTTP 库。
 * 若答辩要求严格对齐文档，替换实现类即可——{@link LlmClient} 接口已隔离差异。
 *
 * <p>重试策略（§3.3）：可重试=连接/读超时、429、5xx；不可重试=401/403（Key 错）、
 * 400（请求体非法）、404（模型名错）立即失败。退避 1s→2s 加随机抖动，防止与
 * 上游限流窗口"对敲"（固定间隔重试容易全部撞进同一个限流桶）。
 *
 * <p>安全：Authorization 头在本类内部由 request.apiKey 现拼，绝不写日志
 * （{@link LlmRequest#toString()} 已掩码，异常消息只带上游状态码与截断后的响应体片段，
 * 响应体片段做 Key 回声清洗——少数厂商会把 Key 回显在错误信息里）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OpenAiCompatClient implements LlmClient {

    /** 最多重试 2 次（总尝试 = 1 + 2 = 3），对齐 §3.3"重试次数 ≤2" */
    private static final int MAX_RETRY = 2;
    /** 退避基数：1s（第 n 次重试退避 1s * 2^n + 抖动） */
    private static final long BACKOFF_BASE_MS = 1000L;
    /** 单条错误响应体留存上限：够定位问题又不至于让日志表膨胀 */
    private static final int ERROR_BODY_SNIPPET = 200;

    private final ObjectMapper objectMapper;

    /**
     * 共享 HttpClient：内置连接池，按 host 复用 TCP/TLS 连接。
     * connectTimeout 固定 5s（§3.2）；读超时不能设在 client 上——它需要按
     * ai_config.timeout_ms 逐请求变化，故用 per-request timeout（下面的 requestTimeout）。
     */
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)   // 外部 API 不该被 302 牵着走（SSRF 护栏）
            .build();

    @Override
    public LlmResponse chat(LlmRequest request) {
        Duration readTimeout = Duration.ofMillis(
                request.getTimeoutMs() == null || request.getTimeoutMs() <= 0 ? 30_000 : request.getTimeoutMs());
        String body = buildRequestBody(request);

        LlmCallException lastError = null;
        int attempt = 0;
        while (attempt <= MAX_RETRY) {
            long start = System.nanoTime();
            try {
                HttpRequest httpReq = HttpRequest.newBuilder(URI.create(endpoint(request.getBaseUrl())))
                        .timeout(readTimeout)
                        .header("Content-Type", "application/json")
                        .header("Authorization", "Bearer " + request.getApiKey())
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build();
                HttpResponse<String> resp = httpClient.send(httpReq, HttpResponse.BodyHandlers.ofString());
                long latency = (System.nanoTime() - start) / 1_000_000;

                if (resp.statusCode() == 200) {
                    LlmResponse parsed = parseSuccess(resp.body());
                    parsed.setLatencyMs(latency);
                    parsed.setRetryCount(attempt);
                    return parsed;    // attempt>0 时上层据 retryCount 写日志 status=3（重试后成功）
                }
                // 非 200：判定可重试性
                LlmCallException err = new LlmCallException(
                        "上游返回 HTTP " + resp.statusCode() + ": " + sanitizeSnip(resp.body()),
                        resp.statusCode(), false);
                if (!isRetryable(resp.statusCode())) {
                    throw err;        // 401/403/400/404：立即失败，重试只是浪费配额与时间
                }
                lastError = err;
            } catch (LlmCallException e) {
                throw e;              // 不可重试错误直穿（上面的 throw）
            } catch (java.io.IOException e) {
                // SocketTimeoutException / ConnectException 等都归"网络层失败"→ 可重试
                lastError = new LlmCallException("网络调用异常: " + e.getClass().getSimpleName()
                        + " - " + safeMsg(e.getMessage()), 0, false, e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();   // 恢复中断位（线程池复用线程，吞中断会污染后续任务）
                throw new LlmCallException("调用被中断", 0, false, e);
            }

            attempt++;
            if (attempt <= MAX_RETRY) {
                sleepBackoff(attempt);
            }
        }
        // 循环走完 = 重试全部耗尽
        throw new LlmCallException(lastError.getMessage(), lastError.getHttpStatus(), true, lastError);
    }

    /**
     * 装配 OpenAI 兼容请求体。
     *
     * <p>{@code stream:false}：本模块走"线程池 + DeferredResult"的一次性返回模型
     * （§7 选型），SSE 流式需要额外的前端通道，收益不抵复杂度。
     * extraBody 最后并入，让厂商扩展参数（enable_thinking 等）可覆盖前面的标准字段。
     */
    private String buildRequestBody(LlmRequest request) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("model", request.getModel());
        var messages = root.putArray("messages");
        for (LlmRequest.Message m : request.getMessages()) {
            messages.addObject().put("role", m.getRole()).put("content", m.getContent());
        }
        if (request.getTemperature() != null) {
            root.put("temperature", request.getTemperature());
        }
        if (request.getMaxTokens() != null) {
            root.put("max_tokens", request.getMaxTokens());
        }
        root.put("stream", false);
        if (request.getExtraBody() != null) {
            request.getExtraBody().forEach((k, v) -> root.put(k, objectMapper.valueToTree(v)));
        }
        try {
            return objectMapper.writeValueAsString(root);
        } catch (Exception e) {
            // 请求体是自己构造的，序列化失败属于编程错误，直接抛（不该被降级掩盖）
            throw new IllegalStateException("AI 请求体序列化失败", e);
        }
    }

    /** 解析 choices[0].message.content 与 usage.*（§3.2 统一响应解析） */
    private LlmResponse parseSuccess(String json) {
        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode content = root.path("choices").path(0).path("message").path("content");
            if (content.isMissingNode() || content.isNull()) {
                throw new LlmCallException("响应缺少 choices[0].message.content", 200, false);
            }
            JsonNode usage = root.path("usage");
            return LlmResponse.builder()
                    .content(content.asText())
                    .promptTokens(usage.hasNonNull("prompt_tokens") ? usage.get("prompt_tokens").asInt() : null)
                    .completionTokens(usage.hasNonNull("completion_tokens") ? usage.get("completion_tokens").asInt() : null)
                    .build();
        } catch (LlmCallException e) {
            throw e;
        } catch (Exception e) {
            throw new LlmCallException("响应 JSON 解析失败: " + safeMsg(e.getMessage()), 200, false, e);
        }
    }

    /**
     * 状态码可重试判定（§3.3）：429（限流，退避后可能成功）、5xx（上游故障，瞬时居多）。
     * 其余（含 3xx）不重试：3xx 说明端点配置错了，重试同样错。
     */
    private boolean isRetryable(int status) {
        return status == 429 || status >= 500;
    }

    /** 端点拼接：base_url 可能带/不带尾斜杠、可能含或不含 /v1（两种写法都收） */
    private String endpoint(String baseUrl) {
        String base = baseUrl == null ? "" : baseUrl.trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base + "/chat/completions";
    }

    /** 指数退避 1s→2s + 随机抖动（≤300ms） */
    private void sleepBackoff(int attempt) {
        long wait = BACKOFF_BASE_MS * (1L << (attempt - 1)) + ThreadLocalRandom.current().nextLong(300);
        try {
            Thread.sleep(wait);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 错误响应体脱敏截断：厂商错误体偶尔回显请求参数（含 Key），
     * 出现 "sk-" 前缀片段一律替换，确保任何日志路径都不落地凭据。
     */
    private String sanitizeSnip(String body) {
        if (body == null) {
            return "";
        }
        String cut = body.length() > ERROR_BODY_SNIPPET ? body.substring(0, ERROR_BODY_SNIPPET) + "…" : body;
        return cut.replaceAll("sk-[A-Za-z0-9._-]{4,}", "sk-****");
    }

    /** 异常消息可能为 null（如部分 TimeoutException），兜底空串防拼接出 "null" */
    private String safeMsg(String msg) {
        return msg == null ? "" : msg.replaceAll("sk-[A-Za-z0-9._-]{4,}", "sk-****");
    }
}
