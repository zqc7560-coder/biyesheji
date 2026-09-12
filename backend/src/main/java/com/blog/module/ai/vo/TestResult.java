package com.blog.module.ai.vo;

import lombok.Builder;
import lombok.Data;

/**
 * 连通性测试出参（POST /api/admin/ai/configs/{id}/test 成功形态；
 * 失败走信封 code=4101/4102，data 为 null，见《API接口设计.md》§7 示例）。
 */
@Data
@Builder
public class TestResult {

    /** 恒 true（失败路径以异常表达，不构造 ok=false 的对象——契约更简单） */
    private boolean ok;
    /** 端到端延迟毫秒 */
    private long latencyMs;
    /** 模型回复片段（截断 50 字符；"请仅回复OK"的验证证据） */
    private String reply;
    /** 本次测试消耗的成本估算（元）——测试也要进账单，答辩可追问 */
    private java.math.BigDecimal costEst;
}
