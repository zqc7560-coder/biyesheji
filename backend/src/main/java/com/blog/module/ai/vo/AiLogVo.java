package com.blog.module.ai.vo;

import com.blog.module.ai.entity.AiCallLog;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * AI 调用日志出参视图（GET /api/admin/ai/logs 的 list 元素，
 * 字段形态对齐《API接口设计.md》§7 示例：userId + nickname + 全部计量列）。
 */
@Data
public class AiLogVo {

    private Long id;
    private Long userId;
    /** 批量回填（日志表只存 user_id，昵称读时 join——日志不可变，昵称允许"后来改了"的不一致） */
    private String nickname;
    private String bizType;
    private Long aiConfigId;
    private String model;
    private Integer promptTokens;
    private Integer completionTokens;
    private BigDecimal costEst;
    private Integer latencyMs;
    private Integer cacheHit;
    /** 1 成功 / 2 失败 / 3 重试后成功 */
    private Integer status;
    private Integer retryCount;
    private String errorMsg;
    private String contentHash;
    private LocalDateTime createdAt;

    public static AiLogVo from(AiCallLog l, String nickname) {
        AiLogVo v = new AiLogVo();
        v.setId(l.getId());
        v.setUserId(l.getUserId());
        v.setNickname(nickname);
        v.setBizType(l.getBizType());
        v.setAiConfigId(l.getAiConfigId());
        v.setModel(l.getModel());
        v.setPromptTokens(l.getPromptTokens());
        v.setCompletionTokens(l.getCompletionTokens());
        v.setCostEst(l.getCostEst());
        v.setLatencyMs(l.getLatencyMs());
        v.setCacheHit(l.getCacheHit());
        v.setStatus(l.getStatus());
        v.setRetryCount(l.getRetryCount());
        v.setErrorMsg(l.getErrorMsg());
        v.setContentHash(l.getContentHash());
        v.setCreatedAt(l.getCreatedAt());
        return v;
    }
}
