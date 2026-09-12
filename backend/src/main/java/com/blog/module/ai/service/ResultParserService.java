package com.blog.module.ai.service;

import com.blog.module.ai.client.LlmResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.regex.Pattern;

/**
 * 模型输出解析与合法化（《AI模块设计.md》§5 输出校验 / WBS 1.6.4）。
 *
 * <p>M6 实现摘要侧；标签侧（JSON 数组三级防御解析 + 词表合法化）随 M7 在同一门面扩展。
 *
 * <p>核心哲学：模型输出是**不可信输入**——它可能带 Markdown、引号、解释文字、
 * 超长或空串。每一形态都要有确定性的收敛动作（清洗/截断/兜底），
 * 而不是祈祷提示词永远生效（项目计划 D-4/R-05 的工程化解法）。
 */
@Slf4j
@Service
public class ResultParserService {

    /** 摘要长度硬上限：超过截断至 197+"…"（§5.1 输出校验口径；150 是提示词期望，200 是接受上限） */
    static final int SUMMARY_MAX_LEN = 200;
    static final int SUMMARY_KEEP_LEN = 197;

    /** 模型偶尔不守"不使用 Markdown"的约定：剥常见行内标记（星号/反引号/井号标题符） */
    private static final Pattern MD_NOISE = Pattern.compile("[`*]{1,3}|(?m)^\\s{0,3}#{1,6}\\s*");
    /** 引号包裹："摘要" 或 「摘要」形态整体去壳 */
    private static final Pattern QUOTE_WRAP = Pattern.compile("^[\"'“「『](.*)[\"'”」』]$", Pattern.DOTALL);

    /**
     * 摘要输出清洗：单行化 → 去 Markdown 噪音 → 去引号壳 → 长度收敛。
     *
     * @return 清洗后的摘要文本；模型输出为空白时返回 ""（调用方据此走机械摘要兜底）
     */
    public String sanitizeSummary(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        // 换行折叠为单行：摘要业务形态是列表页一行文案，多句但必须单段
        String s = raw.replace("\r\n", "\n").replaceAll("\\s*\\n\\s*", "").trim();
        s = MD_NOISE.matcher(s).replaceAll("");
        var m = QUOTE_WRAP.matcher(s.trim());
        if (m.matches()) {
            s = m.group(1).trim();
        }
        // 全角空格/零宽字符顺手清掉（部分模型输出携带不可见字符，前端 textarea 里很诡异）
        s = s.replace('\u3000', ' ').replaceAll("[\\u200b\\u200c\\u200d\\ufeff]", "").trim();
        if (s.length() > SUMMARY_MAX_LEN) {
            s = s.substring(0, SUMMARY_KEEP_LEN) + "…";
        }
        return s;
    }

    /**
     * 摘要兜底判定：模型输出清洗后为空/过短（<20 字符大概率是"抱歉我无法…"式拒答）
     * → 用机械摘要（正文前 120 字+省略号）替代，业务上仍算成功（fallback:true）。
     *
     * <p>为什么不回 4005：拒答是提示词工程的常态尾部概率（R-05），对用户的价值
     * 而言"首段截取的丑摘要"远好于"报错弹框"；答辩演示时 fallback 标志还能
     * 展示降级链设计（§5.1 失败兜底行）。
     */
    public boolean shouldFallback(String sanitized) {
        return sanitized.length() < 20;
    }

    /** 成功响应的 tokens 片段（缓存命中时为 null——没有真实 usage 可比） */
    public static LlmUsage usageOf(LlmResponse resp) {
        return new LlmUsage(resp.getPromptTokens(), resp.getCompletionTokens());
    }

    /** usage 二元组（进 SummaryResult DTO 序列化） */
    public record LlmUsage(Integer prompt, Integer completion) {
    }
}
