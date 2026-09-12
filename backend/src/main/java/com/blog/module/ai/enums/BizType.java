package com.blog.module.ai.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * AI 调用业务类型（对应 ai_call_log.biz_type 列，取值与《数据库设计.md》§3.12 逐字一致）。
 *
 * <p>为什么用枚举而非常量字符串：日志聚合统计（byBiz）、缓存键前缀、配额计数三处
 * 都要引用同一标识，拼写错误在字符串方案里只能在运行时暴露；枚举让编译器把关。
 * code 用小写形式是因为它进 Redis 缓存键（ai:summary:...），键规范偏好小写；
 * name() 大写形式进数据库列与统计出参，两边各司其职。
 */
@Getter
@AllArgsConstructor
public enum BizType {

    /** 文章摘要生成 */
    SUMMARY("summary"),
    /** 文章标签生成（M7 落地调用链，枚举先行保证日志口径完整） */
    TAGS("tags"),
    /** 管理员连通性测试（最小成本调用，计费口径独立便于审计"谁在试 Key"） */
    CONNECT_TEST("connect_test");

    /** Redis 缓存键使用的短标识 */
    private final String code;
}
