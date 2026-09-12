package com.blog.module.ai.client;

import lombok.Builder;
import lombok.Data;

/**
 * 统一大模型调用响应模型（客户端解析后的规整产物）。
 *
 * <p>客户端把厂商差异收敛在这里：无论 DeepSeek 还是 DashScope，成功响应都被解析成
 * {@code content + usage} 二元组；解析不出 usage（个别兼容实现字段名漂移）时
 * tokens 允许为 null——记账不能因 usage 缺失而阻塞返回结果（可用性 > 记账完整性）。
 *
 * <p>latencyMs / retryCount 由客户端测量填充，是 ai_call_log 的直接原料。
 */
@Data
@Builder
public class LlmResponse {

    /** choices[0].message.content（模型原始输出文本，进解析层） */
    private String content;
    /** usage.prompt_tokens（可能为 null） */
    private Integer promptTokens;
    /** usage.completion_tokens（可能为 null） */
    private Integer completionTokens;
    /** 端到端耗时（含重试；缓存命中路径不经过本类，日志直接写 ≈0） */
    private long latencyMs;
    /** 实际发生的重试次数（0=一次成功） */
    private int retryCount;
}
