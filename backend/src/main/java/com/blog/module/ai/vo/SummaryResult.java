package com.blog.module.ai.vo;

import lombok.Builder;
import lombok.Data;

/**
 * 摘要结果出参（POST /api/ai/summary 的 data，字段逐一对齐《API接口设计.md》§7 示例）。
 *
 * <p>tokensUsed/latencyMs 一并回传前端：编辑器右下角显示"本次生成消耗 xxxx tokens"
 * 是答辩演示的直观素材（WBS 1.6.7 成本可视化的前端触点）。
 */
@Data
@Builder
public class SummaryResult {

    /** 摘要文本（AI 生成或机械兜底） */
    private String summary;
    /** true=命中 Redis 缓存（未产生真实调用，零成本零配额） */
    private boolean fromCache;
    /** true=模型输出不可用、已回退机械摘要（§5.1 失败兜底；前端提示"已用截取兜底"） */
    private boolean fallback;
    /** token 用量（缓存命中时为 null——没有真实 usage） */
    private Tokens tokensUsed;
    /** 端到端耗时毫秒（缓存命中 ≈ 0~2ms） */
    private long latencyMs;

    /** usage 二元组：JSON 形态 {"prompt":1802,"completion":64} 对齐文档示例 */
    @Data
    @Builder
    @lombok.AllArgsConstructor
    public static class Tokens {
        private Integer prompt;
        private Integer completion;
    }
}
