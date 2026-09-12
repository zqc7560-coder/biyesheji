package com.blog.module.ai.config;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.blog.module.ai.crypto.KeyCipherService;
import com.blog.module.ai.entity.AiConfig;
import com.blog.module.ai.mapper.AiConfigMapper;
import com.blog.module.ai.service.AiConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 内置模型 API Key 引导（M6）—— 与 M2 AdminBootstrapRunner 同构的"启动补齐"模式。
 *
 * <p>要解决的问题：任务书预置 DeepSeek/Qwen 两把 Key 让系统开箱即用，但密钥红线
 * 禁止明文进 git（data.sql 只放占位密文 __PENDING_KEY_IMPORT__）。真实 Key 存放在
 * 本地 gitignore 的 {@code backend/.env} 或环境变量里，本 Runner 启动时读取并
 * AES-GCM 加密回写占位行——仓库里永远只有"元信息+占位符"。
 *
 * <p>读取顺序（先专用后通用）：
 * {@code BLOG_AI_KEY_DEEPSEEK} > .env 文件（手动解析，不引 dotenv 依赖——
 * 一个 4 行的键值文件不值得加供应链依赖）> 跳过（保持占位，status 接口会如实报
 * configured=false，功能优雅降级而不是启动失败）。
 *
 * <p>幂等：只更新"密文仍是占位串"的行——管理员在后台手动换过 Key 后重启服务
 * 不会被环境变量里的旧值覆盖回去（后台操作 > 启动引导，操作者意图优先）。
 *
 * <p>异常策略：任何失败只 log.warn 不阻断启动——Key 导入失败影响的是 AI 旁路功能，
 * 文章/评论主流程必须照常起（§3.5"AI 完全不可用"行的系统级投影）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BuiltinModelKeySeeder implements ApplicationRunner {

    /** 内置模型 name → 环境变量键（与 data.sql 种子的 name 逐字一致） */
    private static final Map<String, String> BUILTIN_KEY_ENV = Map.of(
            "DeepSeek-V4-Flash", "BLOG_AI_KEY_DEEPSEEK",
            "Qwen3.8-Flash", "BLOG_AI_KEY_QWEN");

    private final AiConfigMapper aiConfigMapper;
    private final KeyCipherService keyCipher;
    private final Environment environment;

    /** 本地密钥文件路径：默认 backend/.env（.gitignore 已忽略），可经属性改指 */
    @Value("${blog.ai.key-file:./.env}")
    private String keyFilePath;

    @Override
    public void run(ApplicationArguments args) {
        try {
            Map<String, String> fileSecrets = loadEnvFile();
            for (var entry : BUILTIN_KEY_ENV.entrySet()) {
                String name = entry.getKey();
                String envKey = entry.getValue();
                // Spring Environment 已聚合 OS 环境变量与 -D 参数；.env 文件作本机开发兜底
                String plain = environment.getProperty(envKey);
                if (plain == null || plain.isBlank()) {
                    plain = fileSecrets.get(envKey);
                }
                if (plain == null || plain.isBlank()) {
                    log.info("内置模型「{}」未提供 API Key（环境变量 {} 缺省），保持占位待管理员导入",
                            name, envKey);
                    continue;
                }
                AiConfig pending = aiConfigMapper.selectOne(new LambdaQueryWrapper<AiConfig>()
                        .eq(AiConfig::getName, name)
                        .eq(AiConfig::getApiKeyCipher, AiConfigService.PENDING_CIPHER));
                if (pending == null) {
                    continue;   // 幂等：行不存在或 Key 已导入（占位串被替换），都不动
                }
                String trimmed = plain.trim();
                // WHERE 带 id + 密文=占位串双条件：并发窗口下"刚被管理员手改"的行不会被覆盖；
                // 条件齐全同时满足 BlockAttackInnerInterceptor 的 WHERE 要求（M4 台账坑）
                int n = aiConfigMapper.update(null, new LambdaUpdateWrapper<AiConfig>()
                        .eq(AiConfig::getId, pending.getId())
                        .eq(AiConfig::getApiKeyCipher, AiConfigService.PENDING_CIPHER)
                        .set(AiConfig::getApiKeyCipher, keyCipher.encrypt(trimmed))
                        .set(AiConfig::getApiKeyMask, KeyCipherService.mask(trimmed))
                        .set(AiConfig::getUpdatedAt, LocalDateTime.now()));
                if (n > 0) {
                    // 日志只出现掩码——明文 Key 从环境读到内存再到密文库，全程不落日志
                    log.info("内置模型「{}」API Key 已加密导入（掩码 {}）",
                            name, KeyCipherService.mask(trimmed));
                }
            }
        } catch (Exception e) {
            // 兜底不抛：AI 引导失败不拖垮启动（主流程可用性 > 旁路功能完备性）
            log.warn("内置模型 Key 引导失败（AI 功能保持未配置态，不影响其他模块）: {}", e.getMessage());
        }
    }

    /**
     * 极简 .env 解析：KEY=VALUE 行，支持 # 注释与引号包裹值。
     * 只读不写；文件不存在回空 Map（正常开发态）。
     */
    private Map<String, String> loadEnvFile() {
        java.nio.file.Path path = java.nio.file.Path.of(keyFilePath);
        if (!java.nio.file.Files.exists(path)) {
            return Map.of();
        }
        Map<String, String> map = new java.util.HashMap<>();
        try {
            for (String line : java.nio.file.Files.readAllLines(path)) {
                String s = line.trim();
                if (s.isEmpty() || s.startsWith("#")) {
                    continue;
                }
                int eq = s.indexOf('=');
                if (eq <= 0) {
                    continue;
                }
                String k = s.substring(0, eq).trim();
                String v = s.substring(eq + 1).trim();
                // 剥成对引号（.env 惯例：值含特殊字符时用引号包裹）
                if (v.length() >= 2 && ((v.startsWith("\"") && v.endsWith("\""))
                        || (v.startsWith("'") && v.endsWith("'")))) {
                    v = v.substring(1, v.length() - 1);
                }
                map.put(k, v);
            }
        } catch (Exception e) {
            log.warn("读取 .env 失败（忽略，走环境变量路径）: {}", e.getMessage());
        }
        return map;
    }
}
