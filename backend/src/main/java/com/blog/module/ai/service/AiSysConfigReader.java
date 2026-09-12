package com.blog.module.ai.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.blog.module.system.entity.SysConfig;
import com.blog.module.system.mapper.SysConfigMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

/**
 * AI 相关系统参数读取器（sys_config 的 ai_* 键族收敛于此）。
 *
 * <p>为什么单拆一个组件而不是各 Service 直接注入 SysConfigMapper：
 * ai_enabled / ai_daily_quota / ai_price_* 四个键在熔断判定、配额、记账三处共用，
 * "缺省值语义"（行缺失怎么办、值非法怎么办）只需要写一遍。M9 会在这里统一接
 * sys:config Redis 镜像（设计文档 §4 键空间），届时只改本类内部，调用方零感知。
 *
 * <p>缺省策略全部取"保守可用"：开关缺省=开（种子必有该键，缺行多半是测试库），
 * 配额缺省=50（与设计一致），单价缺省=0（记 0 费用好过抛异常阻断调用）。
 */
@Service
@RequiredArgsConstructor
public class AiSysConfigReader {

    private final SysConfigMapper sysConfigMapper;

    /** AI 功能总开关（《AI模块设计.md》§3.5 降级矩阵"功能被关"行） */
    public boolean isAiEnabled() {
        String v = value("ai_enabled");
        // 缺行/脏值视为开：与 comment_audit 的"缺省视为关"方向相反——
        // AI 是增强功能，配置读取故障不该把功能整体关掉（关掉的后果是演示全挂）
        return v == null || v.isBlank() || "true".equalsIgnoreCase(v.trim());
    }

    /** 每用户每日真实调用上限（默认 50，项目计划 R-03 成本防线） */
    public int dailyQuota() {
        String v = value("ai_daily_quota");
        try {
            int n = Integer.parseInt(v.trim());
            return n > 0 ? n : 50;
        } catch (Exception e) {
            return 50;
        }
    }

    /** 输入每千 token 单价（元），默认 0 */
    public BigDecimal pricePer1kPrompt() {
        return decimal("ai_price_per_1k_prompt");
    }

    /** 输出每千 token 单价（元），默认 0 */
    public BigDecimal pricePer1kCompletion() {
        return decimal("ai_price_per_1k_completion");
    }

    private String value(String key) {
        SysConfig config = sysConfigMapper.selectOne(new LambdaQueryWrapper<SysConfig>()
                .eq(SysConfig::getConfigKey, key));
        return config == null ? null : config.getConfigValue();
    }

    private BigDecimal decimal(String key) {
        String v = value(key);
        try {
            return new BigDecimal(v.trim());
        } catch (Exception e) {
            return BigDecimal.ZERO;
        }
    }
}
