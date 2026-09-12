package com.blog.module.ai.vo;

import com.blog.module.ai.entity.AiConfig;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * AI 配置出参视图（《API接口设计.md》§9.1 的结构化落实）。
 *
 * <p>本类**没有** apiKey / apiKeyCipher 字段——列表/详情接口"从类型上"就不可能
 * 回传密钥（明文或密文都不行），展示形态只有 apiKeyMask。这是防泄露的最后一道
 * 编译期保险：将来有人加字段要改这个类，code review 时看得见。
 */
@Data
public class AiConfigVo {

    private Long id;
    private String name;
    private String provider;
    private String baseUrl;
    /** 掩码（sk-****abcd）：唯一可见的 Key 形态 */
    private String apiKeyMask;
    private String model;
    private BigDecimal temperature;
    private Integer maxTokens;
    private Integer timeoutMs;
    /** 自定义模板回显（非敏感：模板文本本身不含凭据，管理员需要看到才能编辑） */
    private String summaryPrompt;
    private String tagPrompt;
    /** 1=内置（前端据此隐藏删除按钮、禁用身份字段编辑框） */
    private Integer isBuiltin;
    private Integer isDefault;
    private Integer enabled;
    /** 密文是否仍是占位串（true=从未导入真实 Key，前端行内黄标提醒）——派生布尔，不外泄密文 */
    private Boolean keyImported;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    /** 实体 → VO：唯一允许接触 cipher 的地方就是"判断它是不是占位串"，随即丢弃 */
    public static AiConfigVo from(AiConfig c) {
        if (c == null) {
            return null;
        }
        AiConfigVo v = new AiConfigVo();
        v.setId(c.getId());
        v.setName(c.getName());
        v.setProvider(c.getProvider());
        v.setBaseUrl(c.getBaseUrl());
        v.setApiKeyMask(c.getApiKeyMask());
        v.setModel(c.getModel());
        v.setTemperature(c.getTemperature());
        v.setMaxTokens(c.getMaxTokens());
        v.setTimeoutMs(c.getTimeoutMs());
        v.setSummaryPrompt(c.getSummaryPrompt());
        v.setTagPrompt(c.getTagPrompt());
        v.setIsBuiltin(c.getIsBuiltin());
        v.setIsDefault(c.getIsDefault());
        v.setEnabled(c.getEnabled());
        v.setKeyImported(!com.blog.module.ai.service.AiConfigService.PENDING_CIPHER
                .equals(c.getApiKeyCipher()));
        v.setCreatedAt(c.getCreatedAt());
        v.setUpdatedAt(c.getUpdatedAt());
        return v;
    }
}
