package com.blog.module.ai.client;

import lombok.Getter;

/**
 * 大模型调用失败异常：客户端所有可预期失败（超时/429/5xx 重试耗尽、4xx 立即失败）
 * 的统一出口，携带 HTTP 状态码供上层映射业务码。
 *
 * <p>为什么带 httpStatus：《AI模块设计.md》§2.2/§3.5 的错误映射依赖上游状态——
 * 连接测试要区分"401→4101 Key 无效"与"网络不可达→4102"；用户链路则统一降级 4002。
 * 把状态码随异常传递，避免用异常消息文案做判断（文案会变，码不会）。
 * httpStatus=0 表示压根没连上（DNS/拒连/超时），对应"不可达"语义。
 */
@Getter
public class LlmCallException extends RuntimeException {

    /** 上游 HTTP 状态码；0=未收到任何响应（网络层失败） */
    private final int httpStatus;
    /** 是否重试耗尽后仍失败（日志 status 判定：重试后成功=3、失败=2 的区分原料） */
    private final boolean retryExhausted;

    public LlmCallException(String message, int httpStatus, boolean retryExhausted) {
        super(message);
        this.httpStatus = httpStatus;
        this.retryExhausted = retryExhausted;
    }

    public LlmCallException(String message, int httpStatus, boolean retryExhausted, Throwable cause) {
        super(message, cause);
        this.httpStatus = httpStatus;
        this.retryExhausted = retryExhausted;
    }
}
