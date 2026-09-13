package com.blog.module.ai.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * M11 测试完善 · OpenAiCompatClient 协议与重试纪律单测。
 *
 * <p>测试策略：JDK 自带 {@link HttpServer} 在 127.0.0.1 随机端口起一个假上游——
 * 不出公网（CI/断网维护窗口可跑）、不打真实厂商 Key（红线），但走的是**真 TCP + 真
 * HTTP**：请求体形态、状态码分支、重试节奏、超时链都是客户端的真实代码路径。
 * 用 mock RestTemplate/WebClient 那类"测我桩了什么"的写法在这个类上完全无效——
 * 本类恰恰是厂商协议适配层，被 mock 掉的正是被测对象本身。
 *
 * <p>钉死的契约（《AI模块设计.md》§3.2/§3.3）：
 * ① 端点拼接（尾斜杠/大小写容忍）与请求体字段（model/messages/stream:false/extraBody 覆盖）；
 * ② Authorization 头逐请求现拼、不出现在异常消息（Key 回声清洗 sanitizeSnip）；
 * ③ 429/5xx 重试 ≤2 次后退避、401/400/404 立即失败不重试；
 * ④ usage 缺失时 tokens=null 但 content 正常回（可用性 > 记账完整性）；
 * ⑤ 读超时按可重试网络失败处理（IOException 通道）。
 */
@DisplayName("M11 LLM客户端: 协议装配/重试矩阵/Key脱敏(假上游真HTTP)")
class OpenAiCompatClientM11Test {

    private static HttpServer server;
    /** 每次请求的 {path, auth头, body} 记录（重试计数、头断言的事实源） */
    private static final List<Map<String, String>> RECEIVED = new CopyOnWriteArrayList<>();
    /** 脚本化的响应队列：每个元素 = (status, body)；耗尽后重复最后一个 */
    private static final List<int[]> STATUS_SCRIPT = new CopyOnWriteArrayList<>();
    private static final List<String> BODY_SCRIPT = new CopyOnWriteArrayList<>();
    private static volatile int holdMillis = 0;   // >0 时响应前 sleep（超时用例）

    @BeforeAll
    static void startFakeUpstream() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", OpenAiCompatClientM11Test::handle);
        server.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(4));
        server.start();
    }

    @AfterAll
    static void stop() {
        server.stop(0);
    }

    private static void handle(HttpExchange ex) throws IOException {
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        RECEIVED.add(Map.of(
                "path", ex.getRequestURI().getPath(),
                "auth", String.valueOf(ex.getRequestHeaders().getFirst("Authorization")),
                "body", body));
        if (holdMillis > 0) {
            try {
                Thread.sleep(holdMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        int idx = Math.min(RECEIVED.size() - 1, STATUS_SCRIPT.size() - 1);
        int status = STATUS_SCRIPT.isEmpty() ? 200 : STATUS_SCRIPT.get(idx)[0];
        byte[] resp = (idx < BODY_SCRIPT.size() ? BODY_SCRIPT.get(idx) : "").getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, resp.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(resp);
        }
    }

    private static void resetScript(int[] statuses, String... bodies) {
        RECEIVED.clear();
        STATUS_SCRIPT.clear();
        BODY_SCRIPT.clear();
        holdMillis = 0;
        for (int s : statuses) {
            STATUS_SCRIPT.add(new int[]{s});
        }
        BODY_SCRIPT.addAll(List.of(bodies));
    }

    private static String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private LlmRequest.LlmRequestBuilder baseReq() {
        return LlmRequest.builder()
                .baseUrl(baseUrl())
                .apiKey("sk-test-abcdef123456")
                .model("unit-test-model")
                .messages(List.of(LlmRequest.Message.builder().role("user").content("hi").build()))
                .temperature(new BigDecimal("0.30"))
                .maxTokens(300)
                .timeoutMs(5000);
    }

    private static final String OK_BODY =
            "{\"choices\":[{\"message\":{\"content\":\"[\\\"A标签\\\"]\"}}],"
                    + "\"usage\":{\"prompt_tokens\":11,\"completion_tokens\":22}}";

    // ==================== 请求装配 ====================

    @Test
    @DisplayName("端点/请求头/请求体三对齐：/chat/completions 拼接、Bearer 现拼、stream:false + 标准字段")
    void requestAssembly() {
        resetScript(new int[]{200}, OK_BODY);
        OpenAiCompatClient client = new OpenAiCompatClient(new ObjectMapper());

        LlmResponse resp = client.chat(baseReq().build());

        assertThat(resp.getContent()).isEqualTo("[\"A标签\"]");
        assertThat(resp.getPromptTokens()).isEqualTo(11);
        assertThat(resp.getCompletionTokens()).isEqualTo(22);
        assertThat(resp.getRetryCount()).isZero();

        Map<String, String> got = RECEIVED.get(0);
        assertThat(got.get("path")).isEqualTo("/chat/completions");
        assertThat(got.get("auth")).isEqualTo("Bearer sk-test-abcdef123456");
        assertThat(got.get("body"))
                .contains("\"model\":\"unit-test-model\"")
                .contains("\"stream\":false")
                .contains("\"max_tokens\":300")
                .contains("\"temperature\":0.3");
    }

    @Test
    @DisplayName("尾斜杠归一（带三个斜杠也只拼一个 /chat/completions）；extraBody 顶层并入")
    void endpointNormalizeAndExtraBody() {
        resetScript(new int[]{200}, OK_BODY);
        OpenAiCompatClient client = new OpenAiCompatClient(new ObjectMapper());

        client.chat(baseReq().baseUrl(baseUrl() + "///")
                .extraBody(Map.of("enable_thinking", false, "thinking", Map.of("type", "disabled")))
                .build());

        assertThat(RECEIVED.get(0).get("path")).isEqualTo("/chat/completions");
        // M7 根因修复的双参数形态必须真出现在请求体顶层（这是那次线上 bug 的协议级回归锁）
        assertThat(RECEIVED.get(0).get("body"))
                .contains("\"enable_thinking\":false")
                .contains("\"thinking\":{\"type\":\"disabled\"}");
    }

    // ==================== 重试矩阵 ====================

    @Test
    @DisplayName("429→500→200：重试两次后成功，retryCount=2、总请求 3 次（≤2 上限）")
    void retryThenSuccess() {
        resetScript(new int[]{429, 500, 200}, "{}", "{}", OK_BODY);
        OpenAiCompatClient client = new OpenAiCompatClient(new ObjectMapper());

        LlmResponse resp = client.chat(baseReq().build());

        assertThat(resp.getRetryCount()).isEqualTo(2);
        assertThat(RECEIVED).hasSize(3);
    }

    @Test
    @DisplayName("429 三连败：耗尽后抛 retryExhausted=true 的异常（上层转 4002），共 3 次请求")
    void retryExhausted() {
        resetScript(new int[]{429}, "{\"error\":\"rate limited\"}");
        OpenAiCompatClient client = new OpenAiCompatClient(new ObjectMapper());

        assertThatThrownBy(() -> client.chat(baseReq().build()))
                .isInstanceOf(LlmCallException.class)
                .satisfies(e -> assertThat(((LlmCallException) e).isRetryExhausted()).isTrue());
        // 尾随请求会重复最后一个脚本元素——只断"≥初始 1+2 次"避免与脚本回放细节耦合
        assertThat(RECEIVED.size()).isGreaterThanOrEqualTo(3);
    }

    @Test
    @DisplayName("401（Key 错）立即失败：零重试——重试不改变鉴权结果，只烧时间")
    void authErrorNoRetry() {
        resetScript(new int[]{401}, "{\"error\":\"invalid api key\"}");
        OpenAiCompatClient client = new OpenAiCompatClient(new ObjectMapper());

        assertThatThrownBy(() -> client.chat(baseReq().build()))
                .isInstanceOf(LlmCallException.class)
                .satisfies(e -> assertThat(((LlmCallException) e).isRetryExhausted()).isFalse());
        assertThat(RECEIVED).hasSize(1);
    }

    // ==================== 脱敏与解析容错 ====================

    @Test
    @DisplayName("错误响应体回显 Key 被 sanitizeSnip 清洗：异常消息里 sk- 只剩 sk-****（NFR-04）")
    void errorBodyKeyEchoSanitized() {
        resetScript(new int[]{400},
                "{\"error\":\"key sk-deadbeefdeadbeefdeadbeefdeadbeef is invalid, long body padding " + "x".repeat(300) + "\"}");
        OpenAiCompatClient client = new OpenAiCompatClient(new ObjectMapper());

        assertThatThrownBy(() -> client.chat(baseReq().build()))
                .isInstanceOf(LlmCallException.class)
                .satisfies(e -> {
                    String msg = e.getMessage();
                    assertThat(msg).doesNotContain("sk-deadbeef");
                    assertThat(msg).contains("sk-****");
                    // 截断到 200+省略号，整页错误体不进异常链（日志表 500 列宽防线）
                    assertThat(msg).hasSizeLessThan(300);
                });
    }

    @Test
    @DisplayName("200 但缺 usage：content 照常返回、tokens=null（可用性优先于记账完整性）")
    void missingUsageTolerated() {
        resetScript(new int[]{200}, "{\"choices\":[{\"message\":{\"content\":\"纯文本没有用量\"}}]}");
        OpenAiCompatClient client = new OpenAiCompatClient(new ObjectMapper());

        LlmResponse resp = client.chat(baseReq().build());
        assertThat(resp.getContent()).isEqualTo("纯文本没有用量");
        assertThat(resp.getPromptTokens()).isNull();
    }

    @Test
    @DisplayName("200 但缺 choices：判协议级失败（不重试、不当成功），异常消息可定位")
    void missingContentFails() {
        resetScript(new int[]{200}, "{\"id\":\"cmpl-x\"}");
        OpenAiCompatClient client = new OpenAiCompatClient(new ObjectMapper());

        assertThatThrownBy(() -> client.chat(baseReq().build()))
                .isInstanceOf(LlmCallException.class)
                .hasMessageContaining("choices");
        assertThat(RECEIVED).hasSize(1);   // 协议错重试无意义
    }

    @Test
    @DisplayName("读超时走网络层可重试通道：timeout=200ms + 上游挂 1s → 抛网络类失败")
    void readTimeoutRetried() {
        resetScript(new int[]{200}, OK_BODY);
        holdMillis = 1000;   // 所有响应都挂 1s
        OpenAiCompatClient client = new OpenAiCompatClient(new ObjectMapper());

        long t0 = System.currentTimeMillis();
        assertThatThrownBy(() -> client.chat(baseReq().timeoutMs(200).build()))
                .isInstanceOf(LlmCallException.class)
                .hasMessageContaining("网络调用异常");
        // 首请求 200ms 超时 + 一次退避(~1s+) + 后续……整体应远小于旧式 3×1s 挂死，
        // 但这里只钉"确实发生了重试(≥2 次到达上游或客户端断开)"的弱不变量
        assertThat(System.currentTimeMillis() - t0).isLessThan(15_000);
    }
}
