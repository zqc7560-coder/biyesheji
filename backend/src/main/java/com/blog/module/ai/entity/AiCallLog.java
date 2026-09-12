package com.blog.module.ai.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * AI 调用日志实体（★课题重点）—— 对应《数据库设计.md》§3.12 ai_call_log。
 * append-only（无 updated/deleted）：这是成本审计与答辩数据（成功率/缓存命中率/P95）
 * 的唯一事实来源，改一行数据就等于篡改统计口径。
 * model 列冗余自 ai_config：配置行按"停用不物删"策略也可能改名，日志必须还原调用当时事实。
 * cacheHit=1 的行不产生真实调用，成本记 0，但保留"留痕"以便算命中率。
 */
@Data
@TableName("ai_call_log")
public class AiCallLog {

    /** 主键：数据库自增（《数据库设计.md》§1.2：选自增而非雪花，便于课设演示阅读） */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 发起人：管理员连通性测试时=管理员 ID */
    private Long userId;

    /** 业务类型：SUMMARY / TAGS / CONNECT_TEST */
    private String bizType;

    /** 使用的 ai_config.id */
    private Long aiConfigId;

    /** 调用当时的实际模型名（冗余快照） */
    private String model;

    /** 输入 token（响应 usage 解析，解析失败可为 NULL——日志行本身不能因 usage 缺失而写不进） */
    private Integer promptTokens;

    /** 输出 token */
    private Integer completionTokens;

    /** 预估费用（元）= tokens × sys_config 单价 */
    private java.math.BigDecimal costEst;

    /** 端到端耗时（含重试），P95 统计原料 */
    private Integer latencyMs;

    /** 1=命中缓存 */
    private Integer cacheHit;

    /** 1成功 / 2失败 / 3重试后成功 */
    private Integer status;

    /** 重试次数 */
    private Integer retryCount;

    /** 失败摘要（截断 500 存；必须脱敏——异常消息可能带响应体含 Key 回显） */
    private String errorMsg;

    /** 请求正文 SHA-256：关联缓存键，排查"为何命中/未命中" */
    private String contentHash;

    /** 日志只有写入时间（append-only 表，无 updated/deleted，见类注释） */
    private LocalDateTime createdAt;
}
