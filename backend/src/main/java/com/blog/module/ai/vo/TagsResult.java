package com.blog.module.ai.vo;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 标签生成结果出参（POST /api/ai/tags 的 data，字段逐一对齐《API接口设计.md》§7 示例）。
 *
 * <p>语义边界（《AI模块设计.md》§5.2"入库时机"红线）：本结果是**建议**——只回显给
 * 用户在编辑器勾选确认，随文章保存时由文章模块统一 upsert 标签与重建关联；
 * AI 模块自身不写 tag/article_tag 业务表。唯一例外是管理员显式发起的 batchEnrich
 * （治理动作本身就是确认，见 AiBatchService 类注释）。
 */
@Data
@Builder
public class TagsResult {

    /** 合法化过滤后的候选标签（去重/长度/黑名单/≤6 个，§5.2 防御式解析产物） */
    private List<String> tags;

    /**
     * tags 中"库里已存在"的标签 id（与 tags 顺序对齐，不存在的位置跳过）。
     * 前端据此把候选项标成"复用已有标签"，让勾选时就能预判标签云会不会长新词。
     * 注意它是**每次实时查**的（缓存命中路径也要查）：标签存在性是易变态
     * （别的用户刚保存过文章可能已把候选词建进库），缓存在键里也帮不了这个忙。
     */
    private List<Long> existingTagIds;

    /** true=命中 Redis 结果缓存（未产生真实调用，零成本零配额） */
    private boolean fromCache;

    /** token 用量（缓存命中时为 null——没有真实 usage，与 SummaryResult 同口径） */
    private SummaryResult.Tokens tokensUsed;

    /** 端到端耗时毫秒（缓存命中 ≈ 2ms，§6.2 记账口径一致） */
    private long latencyMs;
}
