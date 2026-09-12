package com.blog.module.ai.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;

/**
 * AI 配置写入 DTO（POST/PUT /api/admin/ai/configs）。
 *
 * <p>它是全项目**唯一**带 apiKey 明文字段的类型，且只进不出：所有出参用
 * {@link AiConfigVo}（无 Key 字段）——《API接口设计.md》§9.1"读接口 DTO 无 Key 字段
 * （编译期杜绝回传）"的结构化落实。
 *
 * <p>PUT 语义（§7 表格）：apiKey 传空串/null = 保持原密文不变；传非空 = 重加密覆盖。
 * 这样管理员编辑温度/超时等参数时不必重新粘贴 Key（也看不到旧 Key）。
 *
 * <p>手写 toString 排除 apiKey：Lombok @Data 的 toString 会把明文 Key 打进取值日志
 * 和异常上下文（GlobalExceptionHandler 兜底打印请求属性时可能波及）；
 * 项目计划 D-2 明确点名"日志脱敏 toString"是密钥工程的必修项。
 */
@Data
public class AiConfigSaveDTO {

    /** 配置名：uk_name 唯一（重名在 Service 先查重给 2103 风格业务码，uk 兜并发） */
    @NotBlank(message = "配置名不能为空")
    @Size(max = 50, message = "配置名最长 50 字符")
    private String name;

    /** 厂商标识：deepseek/dashscope/openai...（仅展示与统计分组，不参与调用逻辑） */
    @Size(max = 30)
    private String provider;

    /** OpenAI 兼容 Base URL：必须 http(s) 开头（防 file:///内网地址这类 SSRF 探索） */
    @NotBlank(message = "Base URL 不能为空")
    @Size(max = 200)
    private String baseUrl;

    /** 明文 API Key：仅写入方向存在；空/null=不变更（PUT 语义） */
    @Size(max = 256, message = "API Key 最长 256 字符")
    private String apiKey;

    /** 模型名：进 /chat/completions 的 model 字段 */
    @NotBlank(message = "模型名不能为空")
    @Size(max = 80)
    private String model;

    /** 采样温度：摘要/标签建议 0.2~0.4，放开到 0~2（OpenAI 协议值域）由管理员自决 */
    @DecimalMin("0") @DecimalMax("2")
    private BigDecimal temperature;

    /** 输出 token 上限：1~4096（防手滑配 100000 造成账单事故） */
    @Min(1) @Max(4096)
    private Integer maxTokens;

    /** 单次调用超时毫秒：1000~60000（超 60s 会撞穿 DeferredResult 35s 超时链，语义失效） */
    @Min(1000) @Max(60000)
    private Integer timeoutMs;

    /** 自定义摘要模板（NULL/空=用内置 ai_summary_v1） */
    @Size(max = 2000)
    private String summaryPrompt;

    /** 自定义标签模板（NULL/空=用内置 ai_tag_v1） */
    @Size(max = 2000)
    private String tagPrompt;

    /** 是否设为默认：true 时事务内先清零其他行（§2.2 步骤 4） */
    private Boolean isDefault;

    /** 启用开关：null 视为新增=启用（默认 1） */
    private Integer enabled;

    @Override
    public String toString() {
        return "AiConfigSaveDTO{name=" + name + ", provider=" + provider + ", baseUrl=" + baseUrl
                + ", model=" + model + ", temperature=" + temperature + ", maxTokens=" + maxTokens
                + ", timeoutMs=" + timeoutMs + ", isDefault=" + isDefault
                + ", apiKey=" + (apiKey == null || apiKey.isBlank() ? "<absent>" : "<present>") + "}";
    }
}
