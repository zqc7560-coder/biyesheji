package com.blog.module.ai.client;

/**
 * 统一大模型客户端接口：业务层只依赖这个抽象，测试用桩实现替换，
 * 真实实现是 {@link OpenAiCompatClient}。
 *
 * <p>存在意义（《AI模块设计.md》§1.1"厂商差异"行）：换模型/换厂商只改
 * ai_config 表里的 base_url+model+key 三参数，代码零改动；
 * 异常语义约定见实现类注释——所有失败路径收敛为 {@link LlmCallException}。
 */
public interface LlmClient {

    /** 发起一次 OpenAI 兼容 chat/completions 调用（含重试策略；阻塞，由 ai-* 线程池承载） */
    LlmResponse chat(LlmRequest request);
}
