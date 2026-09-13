package com.blog.module.ai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M11 测试完善 · 摘要清洗与兜底判定单测（《部署与测试.md》§2 单测范围：
 * "Service 层纯逻辑……错误码映射"中的摘要侧收敛规则，《AI模块设计.md》§5.1）。
 *
 * <p>与 M7 的 AiApiM7Test（接口级、走完整管道含缓存/记账）的分工：本类只钉
 * {@link ResultParserService#sanitizeSummary} / {@link #shouldFallback} 两个纯函数的
 * 字符级行为——它们决定"用户最终在 textarea 里看到什么"，是端到端断言里最容易被
 * 截断/折叠细节糊弄过去的一层（接口测试只断言长度区间，钉不住"省略号在第 198 个
 * 字符还是第 197 个"这类契约）。
 */
@DisplayName("M11 摘要清洗: 单行化/去噪/去壳/长度收敛/兜底判定")
class SummarySanitizeM11Test {

    private final ResultParserService parser = new ResultParserService(new ObjectMapper());

    @Test
    @DisplayName("多行输出折叠为单段：段落间换行全部吃掉（摘要是列表页一行文案）")
    void collapseNewlines() {
        String raw = "第一段落文字。\r\n\r\n  第二段落文字。  \n  第三段。\n";
        String out = parser.sanitizeSummary(raw);
        assertThat(out).doesNotContain("\n").doesNotContain("\r");
        assertThat(out).startsWith("第一段落文字。第二段落文字。");
    }

    @Test
    @DisplayName("Markdown噪音剥离：星号强调/反引号代码/井号标题（模型不守'纯文本'约定时）")
    void stripMarkdownNoise() {
        assertThat(parser.sanitizeSummary("**本文**介绍了`缓存`方案。\n# 总结"))
                .startsWith("本文介绍了缓存方案。");
        // 剥离后不该留下星号/反引号/井号任何残留
        assertThat(parser.sanitizeSummary("# 标题式摘要"))
                .isEqualTo("标题式摘要");
    }

    @ParameterizedTest(name = "壳形态[{0}]整体去壳")
    @CsvSource({
            "'\"这是一段被英文双引号包裹的模型输出样例\"', 这是一段被英文双引号包裹的模型输出样例",
            "'「这是一段被中文直角引号包裹的输出样例」', 这是一段被中文直角引号包裹的输出样例",
            "'“这是一段被中文弯引号包裹的输出样本示例”', 这是一段被中文弯引号包裹的输出样本示例"
    })
    void unwrapQuotes(String raw, String expected) {
        assertThat(parser.sanitizeSummary(raw)).isEqualTo(expected);
    }

    @Test
    @DisplayName("超长截断至 197+省略号（长度硬上限 200，SUMMARY_KEEP_LEN=197）")
    void truncateToHardLimit() {
        String long250 = "摘要文字".repeat(62);   // 248 字符（>200）
        String out = parser.sanitizeSummary(long250);
        assertThat(out).hasSize(ResultParserService.SUMMARY_KEEP_LEN + 1);  // 197 + 省略号 = 198
        assertThat(out).endsWith("…");
        assertThat(out).startsWith("摘要文字摘要文字");
    }

    @Test
    @DisplayName("200 字以内原样保留（不画蛇添足截断）")
    void shortSummaryUntouched() {
        String s = "这是一段刚好在限制内的中文摘要文字。".repeat(5);   // 85 字符
        assertThat(parser.sanitizeSummary(s)).isEqualTo(s);
    }

    @Test
    @DisplayName("全角空格与零宽字符清理：前端 textarea 里的不可见幽灵（实测模型输出携带）")
    void stripInvisibleChars() {
        // 零宽字符用转义码点显式构造——源码里直接打真实 U+200B 肉眼不可辨，落盘环节
        // 也最容易被编辑器的"不可见字符清理"吃掉（测试基建纪律：不可见的一律写码点）
        String zwsp = "\u200b";
        String bom = "\ufeff";
        String raw = "前端" + zwsp + "展示" + bom + "时会出现诡异空格的情况说明。";
        String out = parser.sanitizeSummary(raw);
        assertThat(out).doesNotContain(zwsp).doesNotContain(bom);
        assertThat(out).isEqualTo("前端展示时会出现诡异空格的情况说明。");
        // 全角空格换成半角：不是删掉（会粘住中英文单词）
        assertThat(parser.sanitizeSummary("Ａ　Ｂ　全角空格保留为半角的示例文本"))
                .isEqualTo("Ａ Ｂ 全角空格保留为半角的示例文本");
    }

    @ParameterizedTest(name = "兜底判定: 长度{0} → {1}")
    @CsvSource({
            "'', true",       // 空串（模型空白输出）
            "'抱歉，我无法生成摘要。', true",   // 11 字拒答：清洗后仍 <20 → 走机械兜底（拒答短是常态）
            "'太短了', true",   // 3 字符
            "'这是一段已经远远超过二十个字符的可读中文摘要示例。', false"   // 25 字符直返
    })
    void fallbackThreshold(String text, boolean expectFallback) {
        assertThat(text.length() < 20).isEqualTo(expectFallback);   // 阈值口径自校验
        assertThat(parser.shouldFallback(text)).isEqualTo(expectFallback);
    }

    @Test
    @DisplayName("null 与纯空白清洗后为空串，触发兜底（shouldFallback('')=true 闭环）")
    void blankLeadsToFallback() {
        assertThat(parser.sanitizeSummary(null)).isEmpty();
        assertThat(parser.sanitizeSummary("   \n  \t ")).isEmpty();
        assertThat(parser.shouldFallback(parser.sanitizeSummary("  \n "))).isTrue();
    }

    /**
     * 钉一条"半坑"契约：20 字阈值的兜底判定只看清洗后的**长度**，不看语义——
     * 一句 25 字的礼貌拒答（"抱歉，我无法根据这篇短文生成有价值的摘要内容哦"）
     * 会被当作合格摘要直接返回。这是设计取舍（§5.1：拒答识别成本远高于收益，
     * 摘要场景"丑好过无"），本用例把取舍固化成可回归的断言，防止未来有人
     * "顺手加个关键词检测"改了行为却不改文档。
     */
    @Test
    @DisplayName("拒答话术若≥20字符会被当作正常摘要返回（设计取舍，固化防误改）")
    void politeRefusalAboveThresholdPasses() {
        String refusal = "抱歉，我无法根据这篇短文生成有价值的摘要内容哦。";
        assertThat(refusal).hasSizeGreaterThanOrEqualTo(20);
        assertThat(parser.shouldFallback(parser.sanitizeSummary(refusal))).isFalse();
    }
}
