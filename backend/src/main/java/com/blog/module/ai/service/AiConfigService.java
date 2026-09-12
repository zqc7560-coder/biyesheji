package com.blog.module.ai.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.blog.common.BizCode;
import com.blog.common.BizException;
import com.blog.common.PageResult;
import com.blog.module.ai.crypto.KeyCipherService;
import com.blog.module.ai.dto.AiConfigSaveDTO;
import com.blog.module.ai.entity.AiConfig;
import com.blog.module.ai.mapper.AiConfigMapper;
import com.blog.module.ai.vo.AiConfigVo;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * AI 配置管理 Service（《AI模块设计.md》§2.2 配置管理流程逐步骤实现）。
 *
 * <p>三条铁律贯穿全类：
 * 1) 明文 Key 只在 create/update 的参数里一闪而过——当场 encrypt+mask，
 *    实体上永不回填明文，任何出参走 {@link AiConfigVo}（无 cipher 字段）；
 * 2) 内置行（is_builtin=1）禁删、禁改身份字段（name/provider/baseUrl/model），
 *    只允许换 Key、调参、启停、设默认（任务书约束）；
 * 3) 唯一默认用事务保证：先清零其他行再置本行——表上没有（也不该有）
 *    部分唯一索引，MySQL/H2 对 "WHERE is_default=1" 唯一约束支持不一致，
 *    Service 层事务是跨库一致的最简方案（并发双设默认最后提交者胜，可接受）。
 *
 * <p>"删除"策略（§2.2 步骤 5）：自定义行走 MP 逻辑删除（deleted=1 保留物理行），
 * 审计链 ai_call_log.ai_config_id 永远可回溯到配置行原文。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiConfigService {

    /** M1 种子的占位密文（data.sql 约定字面量）：命中即"未导入真实 Key" */
    public static final String PENDING_CIPHER = "__PENDING_KEY_IMPORT__";

    private final AiConfigMapper aiConfigMapper;
    private final KeyCipherService keyCipher;

    /** 管理端分页（仅掩码，§7 GET /api/admin/ai/configs） */
    public PageResult<AiConfigVo> page(long pageNum, long pageSize) {
        Page<AiConfig> p = aiConfigMapper.selectPage(new Page<>(pageNum, Math.min(pageSize, 100)),
                new LambdaQueryWrapper<AiConfig>().orderByDesc(AiConfig::getIsDefault)
                        .orderByAsc(AiConfig::getId));
        return new PageResult<>(p.getTotal(), p.getPages(), p.getCurrent(), p.getSize(),
                p.getRecords().stream().map(AiConfigVo::from).toList());
    }

    /**
     * 新增配置（§2.2 步骤 1~2）。apiKey 允许为空：创建"未导入 Key"的配置行
     * （密文写占位串），管理员之后补 Key——内置行初始形态就是这么来的，行为一致。
     */
    @Transactional
    public AiConfigVo create(AiConfigSaveDTO dto) {
        assertNameFree(dto.getName(), null);
        AiConfig row = new AiConfig();
        applyEditableFields(row, dto);
        row.setSummaryPrompt(trimToNull(dto.getSummaryPrompt()));
        row.setTagPrompt(trimToNull(dto.getTagPrompt()));
        row.setIsBuiltin(0);            // 管理端创建的永远是"自定义"
        row.setIsDefault(0);
        row.setEnabled(dto.getEnabled() == null ? 1 : dto.getEnabled());
        row.setCreatedAt(LocalDateTime.now());
        row.setUpdatedAt(LocalDateTime.now());
        // Key 处理：有明文→加密+掩码；无→占位（mask=未导入Key），解密路径会给出引导文案
        if (dto.getApiKey() != null && !dto.getApiKey().isBlank()) {
            row.setApiKeyCipher(keyCipher.encrypt(dto.getApiKey().trim()));
            row.setApiKeyMask(KeyCipherService.mask(dto.getApiKey().trim()));
        } else {
            row.setApiKeyCipher(PENDING_CIPHER);
            row.setApiKeyMask("未导入Key");
        }
        aiConfigMapper.insert(row);
        if (Boolean.TRUE.equals(dto.getIsDefault())) {
            setDefault(row.getId());
            row.setIsDefault(1);
        }
        return AiConfigVo.from(aiConfigMapper.selectById(row.getId()));
    }

    /**
     * 修改配置（§7 表：apiKey 传空=不变更）。
     * 内置行改身份字段 → 4006；自定义行全字段可改。
     */
    @Transactional
    public AiConfigVo update(Long id, AiConfigSaveDTO dto) {
        AiConfig row = mustExist(id);
        boolean builtin = row.getIsBuiltin() != null && row.getIsBuiltin() == 1;
        if (builtin) {
            // 内置行的"身份"（名字/厂商/端点/模型）是课题设定的一部分，改了就名实不符；
            // 允许：换 Key（续期/轮换）、调 temperature/maxTokens/timeout、改模板、启停、设默认
            boolean identityChanged = changed(row.getName(), dto.getName())
                    || changed(row.getProvider(), dto.getProvider())
                    || changed(row.getBaseUrl(), dto.getBaseUrl())
                    || changed(row.getModel(), dto.getModel());
            if (identityChanged) {
                throw new BizException(BizCode.AI_BUILTIN_PROTECTED,
                        "内置模型仅允许换 Key/调参/启停/设默认，禁止修改名称、厂商、Base URL 或模型 ID", 403);
            }
        } else {
            assertNameFree(dto.getName(), id);   // 自定义行改名也要过重名关
            applyEditableFields(row, dto);
        }
        row.setTemperature(coalesce(dto.getTemperature(), row.getTemperature()));
        row.setMaxTokens(coalesce(dto.getMaxTokens(), row.getMaxTokens()));
        row.setTimeoutMs(coalesce(dto.getTimeoutMs(), row.getTimeoutMs()));
        row.setSummaryPrompt(dto.getSummaryPrompt());   // 显式传 null=清除自定义、回落内置模板
        row.setTagPrompt(dto.getTagPrompt());
        if (dto.getEnabled() != null) {
            row.setEnabled(dto.getEnabled());
        }
        if (dto.getApiKey() != null && !dto.getApiKey().isBlank()) {
            row.setApiKeyCipher(keyCipher.encrypt(dto.getApiKey().trim()));
            row.setApiKeyMask(KeyCipherService.mask(dto.getApiKey().trim()));
        }
        row.setUpdatedAt(LocalDateTime.now());
        aiConfigMapper.updateById(row);
        // updateById 的默认字段策略是 NOT_NULL（null 列不进 SET）——但"清空自定义模板"
        // 必须能落库（回落内置模板是真实操作意图），所以两个 prompt 列显式补一次 SET，
        // 允许写 null；这也是"管理员清空模板 → 缓存键版本回落 v1"的联动入口
        aiConfigMapper.update(null, new LambdaUpdateWrapper<AiConfig>()
                .eq(AiConfig::getId, id)
                .set(AiConfig::getSummaryPrompt, trimToNull(dto.getSummaryPrompt()))
                .set(AiConfig::getTagPrompt, trimToNull(dto.getTagPrompt())));
        if (Boolean.TRUE.equals(dto.getIsDefault())) {
            setDefault(id);
        }
        return AiConfigVo.from(aiConfigMapper.selectById(id));
    }

    /** 删除=逻辑删除（§2.2 步骤 5：不物理删，审计链不断）；内置行禁止删除 */
    @Transactional
    public void delete(Long id) {
        AiConfig row = mustExist(id);
        if (row.getIsBuiltin() != null && row.getIsBuiltin() == 1) {
            throw new BizException(BizCode.AI_BUILTIN_PROTECTED,
                    "内置模型禁止删除（可停用/设默认），停用后用户端将收到未配置提示", 403);
        }
        aiConfigMapper.deleteById(id);
        // 被删的恰是默认行：全站无默认 → 用户端 4001（下次有人设默认自动恢复），此处不擅自递补
    }

    /**
     * 设为默认（§2.2 步骤 4）：事务内先清零他行再置本行。
     * 目标行必须 enabled=1——"停用的默认"是矛盾态，直接拒绝并提示先启用。
     */
    @Transactional
    public void setDefault(Long id) {
        AiConfig row = mustExist(id);
        if (row.getEnabled() == null || row.getEnabled() != 1) {
            throw new BizException(BizCode.PARAM_ERROR, "该配置已停用，请先启用再设为默认");
        }
        // WHERE id<>? 防 BlockAttackInnerInterceptor（无 WHERE 的批量 UPDATE 会被防全表插件拦死，M4 台账同款约束）
        aiConfigMapper.update(null, new LambdaUpdateWrapper<AiConfig>()
                .ne(AiConfig::getId, id)
                .eq(AiConfig::getIsDefault, 1)
                .set(AiConfig::getIsDefault, 0)
                .set(AiConfig::getUpdatedAt, LocalDateTime.now()));
        aiConfigMapper.update(null, new LambdaUpdateWrapper<AiConfig>()
                .eq(AiConfig::getId, id)
                .set(AiConfig::getIsDefault, 1)
                .set(AiConfig::getUpdatedAt, LocalDateTime.now()));
    }

    /**
     * 取默认配置（用户端调用链的入口，§2.1 is_default 语义）。
     * 返回 null 表示"未配置"，由调用方（AiFacadeImpl）翻译成 4001——
     * 本方法不抛错，因为 status 探测接口要把"未配置"当正常数据回。
     */
    public AiConfig getDefaultEnabled() {
        return aiConfigMapper.selectOne(new LambdaQueryWrapper<AiConfig>()
                .eq(AiConfig::getIsDefault, 1)
                .eq(AiConfig::getEnabled, 1)
                .last("LIMIT 1"));
    }

    /** 该密文是否是"未导入 Key"占位（status 接口的 configured 判定原料） */
    public static boolean pendingKey(AiConfig config) {
        return config == null || PENDING_CIPHER.equals(config.getApiKeyCipher());
    }

    /** 按 id 取行（testConnection 用），幽灵 id → 4001 语义的"配置不存在" */
    public AiConfig mustExist(Long id) {
        AiConfig row = aiConfigMapper.selectById(id);
        if (row == null) {
            throw new BizException(BizCode.AI_NOT_CONFIGURED, "AI 配置不存在（id=" + id + "）");
        }
        return row;
    }

    // ==================== 私有工具 ====================

    /** 除 Key/默认/内置外的"业务字段"统一赋值入口（create 用；update 里内置行不走） */
    private void applyEditableFields(AiConfig row, AiConfigSaveDTO dto) {
        row.setName(dto.getName().trim());
        row.setProvider(dto.getProvider() == null || dto.getProvider().isBlank()
                ? "openai_compatible" : dto.getProvider().trim());
        row.setBaseUrl(normalizeBaseUrl(dto.getBaseUrl()));
        row.setModel(dto.getModel().trim());
    }

    /**
     * Base URL 规范：协议白名单（http/https）——拦 file://、jdbc:// 这类
     * "配置面 SSRF"探索；去尾斜杠（客户端拼接 /chat/completions 处也去，双保险幂等）。
     */
    private String normalizeBaseUrl(String raw) {
        String url = raw.trim();
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            throw new BizException(BizCode.PARAM_ERROR, "Base URL 必须以 http:// 或 https:// 开头");
        }
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        return url;
    }

    /** 重名检查：先查后插给业务码（4007），uk_name 唯一键兜并发窗口（1101/2103 同款两段式） */
    private void assertNameFree(String name, Long excludeId) {
        Long n = aiConfigMapper.selectCount(new LambdaQueryWrapper<AiConfig>()
                .eq(AiConfig::getName, name.trim())
                .ne(excludeId != null, AiConfig::getId, excludeId));
        if (n > 0) {
            throw new BizException(BizCode.AI_CONFIG_NAME_DUPLICATE);
        }
    }

    private static boolean changed(String oldVal, String newVal) {
        return newVal != null && !newVal.isBlank() && !newVal.trim().equals(oldVal);
    }

    /** 空串/全空白视作"清除"（回 NULL 用内置模板），与前端表单"清空输入框"语义对齐 */
    private static String trimToNull(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }

    private static <T> T coalesce(T v, T fallback) {
        return v != null ? v : fallback;
    }
}
