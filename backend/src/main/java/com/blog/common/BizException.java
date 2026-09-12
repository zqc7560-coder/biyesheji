package com.blog.common;

import lombok.Getter;

/**
 * 业务异常：Service 层遇到"可预期的业务失败"时抛出，由全局异常处理器转成统一响应体。
 *
 * <p>为什么不用 RuntimeException 一把梭：区分"业务失败"（密码错、乐观锁冲突——
 * 属于正常流程分支，记 warn 即可）与"系统异常"（NPE、SQL 崩——记 error 并告警），
 * 日志噪音和监控语义完全不同。
 *
 * <p>httpStatus 字段允许业务码携带精确 HTTP 语义（如 1001→401、2001→404），
 * 与《API接口设计.md》§1.4 表中 HTTP 列一一对应。
 */
@Getter
public class BizException extends RuntimeException {

    /** 关联的业务错误码 */
    private final BizCode bizCode;

    /** 期望的 HTTP 状态码（默认 200：业务细分靠 code 表达） */
    private final int httpStatus;

    public BizException(BizCode bizCode) {
        this(bizCode, bizCode.getMessage(), 200);
    }

    public BizException(BizCode bizCode, String message) {
        this(bizCode, message, 200);
    }

    public BizException(BizCode bizCode, String message, int httpStatus) {
        super(message);
        this.bizCode = bizCode;
        this.httpStatus = httpStatus;
    }
}
