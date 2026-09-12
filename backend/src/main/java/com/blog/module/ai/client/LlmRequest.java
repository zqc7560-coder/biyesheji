package com.blog.module.ai.client;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 统一大模型调用请求模型（《AI模块设计.md》§3.2 的 Java 投影）。
 *
 * <p>为什么自定义而不复用 OpenAI SDK 的 Request 对象：本项目的"统一客户端"只依赖
 * OpenAI 兼容协议的**最小公共子集**（model/messages/temperature/max_tokens/stream），
 * 自定义 DTO 让字段可控——深度思考类模型（qwen3.8-flash）在 DashScope 兼容模式下
 * 默认 enable_thinking=true，思维链会把 max_tokens 吃光、content 返空，因此
 * {@code extraBody} 允许按配置追加厂商扩展参数（如 enable_thinking:false），
 * 客户端仍是一套代码，参数差异全部数据化进 ai_config。
 *
 * <p>toString 手写且 apiKey 输出掩码：该类会随请求上下文出现在日志与异常信息里，
 * Lombok 生成的 toString 会把明文 Key 全量打出来（《AI模块设计.md》§8 脱敏红线，
 * 项目计划 D-2 专门点名的坑）。
 */
@Data
@Builder
public class LlmRequest {

    /** OpenAI 兼容 Base URL（如 https://api.deepseek.com）；客户端拼 /chat/completions */
    private String baseUrl;
    /** 明文 API Key：仅在此对象内存态存在，绝不入库/不进日志（见 toString 掩码） */
    private String apiKey;
    /** 模型名：deepseek-v4-flash / qwen3.8-flash */
    private String model;
    /** 对话消息列表：[system=模板段, user=标题+正文] */
    private List<Message> messages;
    /** 采样温度（BigDecimal 保 DECIMAL(3,2) 精度直传） */
    private java.math.BigDecimal temperature;
    /** 输出 token 上限（成本闸门） */
    private Integer maxTokens;
    /** 厂商扩展参数：序列化后并入请求体顶层（enable_thinking 等；null 安全） */
    private java.util.Map<String, Object> extraBody;
    /** 单次 HTTP 超时毫秒（OkHttp per-call timeout，取 ai_config.timeout_ms） */
    private Integer timeoutMs;

    /** 一条对话消息：role ∈ {system, user}，content 为纯文本 */
    @Data
    @Builder
    public static class Message {
        private String role;
        private String content;
    }

    /** 脱敏 toString：Key 只显示长度与掩码，防日志泄露 */
    @Override
    public String toString() {
        return "LlmRequest{baseUrl=" + baseUrl + ", apiKey=" + com.blog.module.ai.crypto.KeyCipherService.mask(apiKey)
                + ", model=" + model + ", temperature=" + temperature
                + ", maxTokens=" + maxTokens + ", messages=" + (messages == null ? 0 : messages.size()) + "}";
    }
}
