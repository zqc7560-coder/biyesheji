package com.blog.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 统一响应体与错误码的纯单元测试（不启 Spring 容器）。
 *
 * <p>为什么优先测它：Result/BizCode 是全站契约的地基——一旦有人改了 code 值或
 * 信封字段名，前端拦截器和几十条 Postman 断言会同时崩。这类"契约测试"用
 * 毫秒级纯单测锁死，比集成测试更早、更便宜地挡住回归。
 */
@DisplayName("common: 统一响应体与错误码契约")
class ResultTest {

    @Test
    @DisplayName("成功响应：code=0、message=success、timestamp 已填充")
    void okCarriesSuccessEnvelope() {
        Result<String> r = Result.ok("payload");

        assertThat(r.getCode()).isZero();
        assertThat(r.getMessage()).isEqualTo("success");
        assertThat(r.getData()).isEqualTo("payload");
        // timestamp 必须为正数：证明构造时确实打了时间戳，而非漏填的 0
        assertThat(r.getTimestamp()).isPositive();
    }

    @Test
    @DisplayName("无数据成功：data 为 null 而非缺字段（前端 JSON.parse 语义稳定）")
    void okWithoutData() {
        Result<Void> r = Result.ok();

        assertThat(r.getCode()).isZero();
        assertThat(r.getData()).isNull();
    }

    @Test
    @DisplayName("失败响应：取错误码默认文案，data 为 null")
    void failUsesEnumMessage() {
        Result<Void> r = Result.fail(BizCode.ARTICLE_NOT_FOUND);

        assertThat(r.getCode()).isEqualTo(2001);
        assertThat(r.getMessage()).isEqualTo("文章不存在或已删除");
        assertThat(r.getData()).isNull();
    }

    @Test
    @DisplayName("失败响应允许覆盖文案（如带字段上下文），code 不变")
    void failAllowsMessageOverride() {
        Result<Void> r = Result.fail(BizCode.PARAM_ERROR, "title: 标题不能超过200字");

        assertThat(r.getCode()).isEqualTo(400);
        assertThat(r.getMessage()).contains("标题不能超过200字");
    }

    @Test
    @DisplayName("错误码区段契约：与《API接口设计.md》§1.4 关键码值逐条核对")
    void bizCodeMatchesApiContract() {
        // 认证区 1xxx、内容区 2xxx、文件区 3xxx、AI 区 4xxx、配置区 5xxx：
        // 前端按区段做统一动作，码值漂移即契约破坏，故逐条断言。
        assertThat(BizCode.UNAUTHORIZED.getCode()).isEqualTo(1001);
        assertThat(BizCode.LOGIN_FAILED.getCode()).isEqualTo(1002);
        assertThat(BizCode.ACCOUNT_DISABLED.getCode()).isEqualTo(1003);
        assertThat(BizCode.TOKEN_EXPIRED.getCode()).isEqualTo(1004);
        assertThat(BizCode.FORBIDDEN.getCode()).isEqualTo(1005);
        assertThat(BizCode.VERSION_CONFLICT.getCode()).isEqualTo(2004);
        assertThat(BizCode.FILE_TOO_LARGE.getCode()).isEqualTo(3002);
        assertThat(BizCode.AI_NOT_CONFIGURED.getCode()).isEqualTo(4001);
        assertThat(BizCode.AI_CALL_FAILED.getCode()).isEqualTo(4002);
        assertThat(BizCode.AI_QUOTA_EXCEEDED.getCode()).isEqualTo(4003);
        assertThat(BizCode.AI_BASE_URL_UNREACHABLE.getCode()).isEqualTo(4102);
        assertThat(BizCode.CONFIG_NOT_FOUND.getCode()).isEqualTo(5001);
    }

    @Test
    @DisplayName("错误码全局唯一：重复 code 会让前端拦截器误判，必须挡住")
    void bizCodesAreUnique() {
        long distinct = java.util.Arrays.stream(BizCode.values())
                .map(BizCode::getCode).distinct().count();

        assertThat(distinct).isEqualTo(BizCode.values().length);
    }

    @Test
    @DisplayName("业务异常携带 HTTP 语义：登出/越权等场景状态码要精确")
    void bizExceptionCarriesHttpStatus() {
        BizException e = new BizException(BizCode.UNAUTHORIZED, "未认证或令牌失效", 401);

        assertThat(e.getBizCode()).isEqualTo(BizCode.UNAUTHORIZED);
        assertThat(e.getHttpStatus()).isEqualTo(401);
        // 默认构造器用 200：业务细分靠 code，HTTP 保持粗粒度（API 设计 §1.1）
        assertThat(new BizException(BizCode.TAG_NAME_DUPLICATE).getHttpStatus()).isEqualTo(200);
    }
}
