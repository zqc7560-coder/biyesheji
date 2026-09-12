package com.blog.common;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 统一响应体信封 —— 结构严格对齐《API接口设计.md》§1.1。
 *
 * <p>设计动机：前后端分离项目里，如果每个接口各自定义返回形状，
 * 前端 Axios 拦截器就无法做"一处处理，处处生效"（401 跳登录、错误 toast 等）。
 * 因此约定：所有接口（含出错）都返回 {code, message, data, timestamp}，
 * HTTP 状态码只表达粗粒度结果（200/401/403/404/429/500），业务细分靠 code。
 *
 * <p>泛型 T 而非 Object：编译期约束 Service 层返回载荷类型，
 * 配合 Knife4j 能在文档里生成具体 schema，接口可读性更好。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Result<T> {

    /** 业务码：0 成功，非 0 见 {@link BizCode} */
    private int code;

    /** 中文提示：成功固定 "success"；失败为可直接展示的文案 */
    private String message;

    /** 业务载荷：出错时为 null 或字段级校验明细 */
    private T data;

    /** 服务器毫秒时间戳：前端可据此校时/排查缓存陈旧问题 */
    private long timestamp;

    /** 成功（带数据） */
    public static <T> Result<T> ok(T data) {
        return new Result<>(BizCode.SUCCESS.getCode(), BizCode.SUCCESS.getMessage(),
                data, System.currentTimeMillis());
    }

    /** 成功（无数据，如 DELETE / 登出这类只关心结果的接口） */
    public static Result<Void> ok() {
        return ok(null);
    }

    /** 失败：按错误码取默认文案 */
    public static <T> Result<T> fail(BizCode bizCode) {
        return new Result<>(bizCode.getCode(), bizCode.getMessage(), null,
                System.currentTimeMillis());
    }

    /** 失败：自定义文案（如带字段上下文的校验错误），code 仍取自枚举 */
    public static <T> Result<T> fail(BizCode bizCode, String message) {
        return new Result<>(bizCode.getCode(), message, null, System.currentTimeMillis());
    }

    /** 失败：完全自定义（内部异常兜底等少数场景使用） */
    public static <T> Result<T> fail(int code, String message) {
        return new Result<>(code, message, null, System.currentTimeMillis());
    }
}
