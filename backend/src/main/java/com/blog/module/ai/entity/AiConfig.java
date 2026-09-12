package com.blog.module.ai.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * AI 大模型配置实体（★课题重点）—— 对应《数据库设计.md》§3.11 ai_config。
 *
 * <p>安全红线：apiKeyCipher 存 AES-256-GCM 密文（Base64，封装 IV+密文+Tag），
 * 明文 Key 永不入库、永不回传前端、永不进日志——列表展示一律走 apiKeyMask
 * （形如 sk-****abcd）。自定义模板字段（summaryPrompt/tagPrompt）为 NULL 时
 * 回退代码内置 PromptTemplate 枚举，支持不改代码灰度新提示词。
 *
 * <p>isBuiltin（M1 按任务书新增列）：1=课题预置内置模型（DeepSeek/Qwen 两行），
 * 后台禁止删除、仅允许启停/设默认；0=管理员自建，可正常删除（停用保留行策略见
 * 设计文档 §5 说明：保证 ai_call_log.ai_config_id 审计链不断）。
 */
@Data
@TableName("ai_config")
public class AiConfig {

    /** 主键：数据库自增（《数据库设计.md》§1.2：选自增而非雪花，便于课设演示阅读） */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 配置名：uk_name 唯一，如「DeepSeek-V4-Flash」 */
    private String name;

    /** 厂商标识：deepseek/dashscope/...（统一走 OpenAI 兼容协议，此列仅展示与统计分组） */
    private String provider;

    /** OpenAI 兼容 Base URL：两家内置模型仅靠它+model+key 参数化，不为厂商写两套代码 */
    private String baseUrl;

    /** API Key 的 AES-256-GCM 密文（Base64, IV+密文+Tag）；种子为占位串时解密必须显式报错 */
    private String apiKeyCipher;

    /** 掩码（sk-****abcd）：唯一允许出现在接口出参里的 Key 形态 */
    private String apiKeyMask;

    /** 模型名：deepseek-v4-flash / qwen3.8-flash，进调用日志冗余 */
    private String model;

    /** 采样温度：摘要/标签建议 0.2~0.4（DECIMAL(3,2) 用 BigDecimal 保精度） */
    private java.math.BigDecimal temperature;

    /** 单次回复 token 上限：成本闸门之一 */
    private Integer maxTokens;

    /** 单次调用超时毫秒（OkHttp readTimeout 取此值） */
    private Integer timeoutMs;

    /** 自定义摘要模板：NULL 用内置 ai_summary_v1 */
    private String summaryPrompt;

    /** 自定义标签模板：NULL 用内置 ai_tags_v1 */
    private String tagPrompt;

    /** 1=内置模型（禁止删除，仅启停/设默认）/ 0=管理员自建 */
    private Integer isBuiltin;

    /** 全站唯一默认行：Service 层事务内先清零再置 1（《AI模块设计.md》§4.4） */
    private Integer isDefault;

    /** 启用开关：停用后用户端 AI 请求直接 4001 */
    private Integer enabled;

    /** 创建时间：DDL 默认 CURRENT_TIMESTAMP，insert 时省略即可 */
    private LocalDateTime createdAt;

    /** 更新时间：MySQL 由 ON UPDATE 维护；H2 测试库由应用层负责（差异见 schema-h2.sql 头注释） */
    private LocalDateTime updatedAt;

    /** 逻辑删除标记（全局配置 logic-delete-field=deleted，所有 MP 查询自动拼 deleted=0） */
    @TableLogic
    private Integer deleted;
}
