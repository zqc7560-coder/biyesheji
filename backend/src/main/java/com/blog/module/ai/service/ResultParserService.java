package com.blog.module.ai.service;

import com.blog.module.ai.client.LlmResponse;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 模型输出解析与合法化（《AI模块设计.md》§5 输出校验 / WBS 1.6.4）。
 *
 * <p>M6 实现摘要侧；标签侧（JSON 数组三级防御解析 + 词表合法化）随 M7 在同一门面扩展。
 *
 * <p>核心哲学：模型输出是**不可信输入**——它可能带 Markdown、引号、解释文字、
 * 超长或空串。每一形态都要有确定性的收敛动作（清洗/截断/兜底），
 * 而不是祈祷提示词永远生效（项目计划 D-4/R-05 的工程化解法）。
 *
 * <p>M7 落地标签侧：{@link #parseTags(String)} 实现《AI模块设计.md》§5.2 的
 * "防御式三级解析"（剥围栏 → JSON 数组 → 逗号/顿号/换行退化切分）+ 合法化过滤
 * （trim/去重/长度 2~8/停用词黑名单/最多 6 个）。标签侧**没有**摘要那样的
 * 机械兜底：标签是强结构输出，"正文截词"当标签既无意义也会污染标签表
 * （tag.name 唯一 + 半自由文本，垃圾进不易清），所以三级全失败就抛 4005 让用户重试。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ResultParserService {

    /**
     * M7 注入：JSON 数组解析用（复用全局 ObjectMapper——Jackson 随 spring-boot-starter-web
     * 必在 classpath；这里只调 readValue 纯函数式 API，与 Redis 序列化配置互不影响）。
     */
    private final ObjectMapper objectMapper;

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

    // ==================== 标签侧（M7：§5.2 防御式三级解析） ====================

    /** 标签合法长度下限（提示词承诺 2~8 字；越界一律丢弃——tag.name 是唯一列，灌进去难清） */
    static final int TAG_MIN_LEN = 2;
    /** 含 CJK 标签的长度上限（§5.2 的"8 个字"以中文字符计） */
    static final int TAG_MAX_LEN = 8;
    /**
     * 纯 ASCII 标签的放宽上限：设计文档示例输出 ["SpringBoot","缓存","Redis"] 里
     * SpringBoot 就有 10 字符——"2~8 字"的口径本来就是中文字符计。24 上限同时
     * 保证不超 tag.name VARCHAR(30) 且给 M3 保存链路留出余量（超长会被 rebuildTags 静默丢弃）。
     */
    static final int ASCII_TAG_MAX_LEN = 24;
    /** 最多保留 6 个（§5.2 合法化过滤最后一步：超出即截断，保证前端勾选区不炸版） */
    static final int TAG_MAX_COUNT = 6;

    /**
     * 停用词黑名单（提示词第 3 条的**服务端执法**）。
     *
     * <p>为什么不能只写在 system 段里求模型自觉：提示词遵循度是概率性的，
     * "分享/总结/记录"这类元词是最容易被吐出来的高频词，一旦入库就成了标签云里的
     * 语义噪音，而 tag 表按 name 唯一、被 article_tag 引用的治理成本远高于防住它。
     * 一份词表在代码里 = 确定性；模型听话时它不生效，模型不听话时它兜住。
     */
    private static final Set<String> TAG_STOPWORDS = Set.of(
            "分享", "总结", "笔记", "记录", "原创", "转载", "随笔", "日常",
            "学习", "教程", "文章", "博客", "其他", "更多", "推荐", "干货",
            // 拒答话术碎片：退化的③分割会把"抱歉，这篇文章…"按逗号切开，2 字的"抱歉"
            // 恰好躲过长度闸门——把常见道歉/推脱词也纳黑名单，防止拒答碎片当标签
            "抱歉", "对不起", "不好意思", "谢谢", "无法", "建议", "如下");

    /** 退化切分（容错③）的分隔符：中英文逗号、顿号、分号、换行、Markdown 列表符号 */
    private static final Pattern LOOSE_SPLIT = Pattern.compile("[,，、;；\\n]+|^\\s*[-*+]\\s*");
    /** 代码围栏（容错①）：```json ... ``` / ``` ... ``` 整体剥壳，保留内部文本 */
    private static final Pattern CODE_FENCE =
            Pattern.compile("^\\s*```[a-zA-Z]*\\s*\\r?\\n?(.*?)\\r?\\n?\\s*```\\s*$", Pattern.DOTALL);

    /**
     * 标签输出解析 + 合法化（§5.2 全链）。
     *
     * @param raw 模型原始输出（choices[0].message.content）
     * @return 合法标签列表（可能为空——空由调用方转 4005，本方法不抛，保持纯函数可单测）
     */
    public List<String> parseTags(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        // 容错①：剥 ```json 围栏——模型最常见的"违约"形态就是自作聪明加代码块
        String text = stripCodeFence(raw.trim());
        // 容错②：按 JSON 数组严格解析；失败说明它吐的是解释文字混数组，退而求其次
        List<String> candidates = tryParseJsonArray(text);
        if (candidates == null) {
            // 拒答闸门：散文里没有 JSON 结构、又带着道歉/推脱话术 → 整段都是拒答文案，
            // 退化切分只会把"这篇文章内容太少"这类句子碎片洗成伪标签（8 字以内恰好躲过
            // 长度闸门的实测案例）。宁空勿污：让调用方 4005 引导重试，比返回一堆
            // 句子碎片标签对用户和标签表都更负责。
            if (REFUSAL.matcher(text).find()) {
                return List.of();
            }
            // 容错③：彻底放弃结构预期，按逗号/顿号/换行切分（"A、B、C"式人类答案也接得住）
            candidates = trySplitLoose(text);
        }
        return legalize(candidates);
    }

    /** 拒答话术标记（只在全程无 JSON 结构时启用，避免误伤含"无法"字样的真实标签词） */
    private static final Pattern REFUSAL =
            Pattern.compile("抱歉|对不起|不好意思|我无法|无法为|无法根据|请提供|请补充|请给出更多");

    /** 容错①：剥 Markdown 代码块围栏（无围栏则原样返回） */
    static String stripCodeFence(String s) {
        var m = CODE_FENCE.matcher(s);
        return m.matches() ? m.group(1).trim() : s;
    }

    /**
     * 容错②：JSON 数组解析。
     *
     * <p>额外做一步"截取最外层方括号"：模型常在数组前后再缀一句解释
     * （"以下是标签：[a,b] 希望有帮助"），直接 readValue 会因前后缀失败；
     * 先掐头去尾定位 [ … ] 再解析，能把这类输出救回来。
     * 元素必须是字符串/数字（数字转文本），嵌套对象等非标量一律判为解析失败。
     *
     * @return 解析出的原始字符串列表；失败返回 null（交由下一级容错）
     */
    List<String> tryParseJsonArray(String text) {
        int l = text.indexOf('[');
        int r = text.lastIndexOf(']');
        if (l < 0 || r <= l) {
            return null;
        }
        String json = text.substring(l, r + 1);
        try {
            List<Object> nodes = objectMapper.readValue(json, new TypeReference<List<Object>>() {
            });
            List<String> out = new ArrayList<>(nodes.size());
            for (Object n : nodes) {
                if (n instanceof String s) {
                    out.add(s);
                } else if (n instanceof Number num) {
                    // "3" 这种纯数字标签会被 Jackson 解析成 Integer；转字符串后交合法化判长度
                    out.add(num.toString());
                } else if (n != null) {
                    return null;   // 对象/数组混进来：说明模型理解的"标签"不是我们想要的
                }
            }
            return out;
        } catch (Exception e) {
            log.debug("标签 JSON 数组解析失败，转退化切分: {}", e.getMessage());
            return null;
        }
    }

    /** 容错③：无结构退化切分（模型彻底不吐 JSON 时的最后一道网） */
    static List<String> trySplitLoose(String text) {
        List<String> out = new ArrayList<>();
        for (String part : LOOSE_SPLIT.split(text)) {
            String p = part.trim();
            // 剥"键值前缀"：散文式回答常写成"标签：A、B、C"，首个逗号/顿号前的段
            // 会连"标签："一起进来——冒号是话术与载荷的分界，取冒号后内容。
            // 限定前缀 ≤6 字符：真标签里出现长冒号前缀本身就是句子，让长度闸门去毙它。
            var m = KEY_PREFIX.matcher(p);
            if (m.find() && !m.group(1).isBlank()) {
                p = m.group(1).trim();
            }
            if (!p.isEmpty()) {
                out.add(p);
            }
        }
        return out;
    }

    /** 退化切分路径的"标签：xxx"/"tags: xxx"键值前缀剥除 */
    private static final Pattern KEY_PREFIX =
            Pattern.compile("^[^:：]{0,6}[:：]\\s*(.+)$", Pattern.DOTALL);

    /**
     * 合法化过滤：trim → 去引号/尾随标点 → 长度 2~8 → 停用词/纯符号剔除 →
     * 忽略大小写去重（保留首次出现的原始大小写）→ 截断至 6 个。
     *
     * <p>去重按"小写形态"做而输出保留原样：标签表唯一键是大小写不敏感的
     * （MySQL utf8mb4_general_ci 默认排序规则），这里若区分大小写，"Java" 与
     * "java" 会被当作两个候选、其中后者在保存时静默合并，前端勾选数就对不上了。
     */
    static List<String> legalize(List<String> candidates) {
        Set<String> seen = new LinkedHashSet<>();
        List<String> out = new ArrayList<>();
        for (String raw : candidates) {
            String tag = normalizeTag(raw);
            if (tag == null) {
                continue;
            }
            if (seen.add(tag.toLowerCase()) && out.size() < TAG_MAX_COUNT) {
                out.add(tag);
            }
        }
        return out;
    }

    /**
     * 单个标签的清洗与合规判定。
     *
     * <p>长度闸门的方言处理：提示词的"2~8 个字"以**中文字符**计（§5.2 原文），
     * 若对拉丁词也一刀切 8 字符，会误杀设计文档自己的示例输出 "SpringBoot"（10 字符）。
     * 因此分两套口径：含 CJK 的标签按 2~8 计；纯 ASCII 词放宽到 2~24
     * （覆盖 Redis/Kubernetes 这类技术名，仍挡住"整句英文当标签"的幻觉形态）。
     *
     * @return 合规的标签文本；不合规返回 null（调用方丢弃，而不是抛异常中断整批）
     */
    static String normalizeTag(String raw) {
        if (raw == null) {
            return null;
        }
        // 模型常给标签套引号/书名号，或句末带句号——先去壳去尾标点再判长度，
        // 否则 "\"Redis\"" 会因带引号超长被误杀
        String s = raw.trim()
                .replaceAll("^[\"'“”「『【《]+", "")
                .replaceAll("[\"'“”」』】。、，,；;：:》>)）]+$", "")
                .trim();
        boolean hasCjk = s.codePoints().anyMatch(cp ->
                cp >= 0x4E00 && cp <= 0x9FFF);   // CJK 统一表意文字基本区（毕设语料足够）
        int max = hasCjk ? TAG_MAX_LEN : ASCII_TAG_MAX_LEN;
        if (s.length() < TAG_MIN_LEN || s.length() > max) {
            return null;
        }
        // 纯标点/纯符号（模型偶尔吐 "—"、"..." 当分隔残渣）——不含任何字母/数字/汉字即弃
        if (!s.codePoints().anyMatch(cp -> Character.isLetterOrDigit(cp))) {
            return null;
        }
        // emoji 与空白同样丢弃：提示词明令禁止，服务端要能自证执行（答辩可演示）
        if (s.chars().anyMatch(Character::isWhitespace) || s.codePoints().anyMatch(cp -> cp > 0xFFFF)) {
            return null;
        }
        if (TAG_STOPWORDS.contains(s)) {
            return null;
        }
        return s;
    }

    /** 成功响应的 tokens 片段（缓存命中时为 null——没有真实 usage 可比） */
    public static LlmUsage usageOf(LlmResponse resp) {
        return new LlmUsage(resp.getPromptTokens(), resp.getCompletionTokens());
    }

    /** usage 二元组（进 SummaryResult DTO 序列化） */
    public record LlmUsage(Integer prompt, Integer completion) {
    }
}
