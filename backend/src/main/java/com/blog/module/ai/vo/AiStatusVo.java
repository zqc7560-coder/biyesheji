package com.blog.module.ai.vo;

import lombok.Builder;
import lombok.Data;

/**
 * AI 状态探测出参（GET /api/ai/status，结构逐字段对齐《API接口设计.md》§7 示例）。
 *
 * <p>前端唯一消费场景：编辑器加载时拉一次，决定"✨AI 摘要 / 🏷️AI 标签"按钮
 * 置灰与否 + tooltip 文案（enabled=false→"功能已关闭"；configured=false→
 * "管理员尚未配置模型/Key"；remainingToday=0→"今日额度已用完"）。
 *
 * <p>公开接口但刻意不含任何敏感信息（无 baseUrl/无掩码 Key/无模型清单，
 * 只有默认模型名）——游客只需要知道"能不能用"，不需要知道"用的谁的 Key"。
 */
@Data
@Builder
public class AiStatusVo {

    /** sys_config.ai_enabled 总开关 */
    private boolean enabled;
    /** 存在启用中的默认配置 **且** 已导入真实 Key（占位密文不算 configured） */
    private boolean configured;
    /** 默认模型名（configured=false 时为 null） */
    private String defaultModel;
    /** 今日剩余额度（游客/未登录为 null——配额是登录用户概念） */
    private Integer remainingToday;
    /** 日配额上限（供前端显示 "3/50" 进度） */
    private int dailyQuota;
}
