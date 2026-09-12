package com.blog.module.article.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Markdown 正文处理器单测（M3）—— 覆盖《项目计划.md》阶段四任务 7 与《部署与测试.md》§2
 * "安全测试：XSS 注入（文章 payload 集）"的服务端半边（NFR-05 双防线第一道）。
 *
 * <p>纯逻辑单测，不起 Spring 上下文：MarkdownProcessor 是无状态静态工具，
 * 用上下文包它是浪费 30 秒启动时间去测正则（分层测试策略：纯函数归单元层）。
 */
@DisplayName("M3 MarkdownProcessor: 纯文本抽取 / XSS 清洗 / 字数统计")
class MarkdownProcessorTest {

    @Test
    @DisplayName("Markdown 语法噪音剥净，语料保留：标题/列表/链接/强调不进索引")
    void stripsMarkdownSyntaxKeepsCorpus() {
        String md = """
                # 一级标题
                ## 二级
                - 列表项甲
                1. 有序项乙
                > 引用文字
                **粗体** 与 *斜体* 和 ~~删除~~
                [链接文字](https://evil.example.com/x)
                ![图片描述](/uploads/2026/09/a.png)
                行内 `code_word` 围栏：
                ```java
                System.out.println("代码里的词");
                ```
                """;
        String text = MarkdownProcessor.extractText(md);

        assertThat(text)
                .contains("一级标题", "列表项甲", "有序项乙", "引用文字", "粗体", "斜体",
                        "链接文字", "图片描述", "code_word", "代码里的词")
                // 渲染语法与 URL 路径是噪音不是语料：不应污染中文全文检索相关性
                .doesNotContain("#", "```", "https://evil.example.com", "![]", "~~");
    }

    /**
     * XSS payload 集（DVWA 式清单，《项目计划.md》D-7 预研沉淀）：
     * 清洗后纯文本中不残留任何可执行结构——标签被剥、事件属性随标签消失、
     * javascript: 协议只剩无害字符串（不解析=不执行）。
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "<script>alert(1)</script>",
            "<img src=x onerror=alert(1)>",
            "<svg onload=alert(1)></svg>",
            "<iframe src='javascript:alert(1)'></iframe>",
            "<a href=\"javascript:alert('xss')\">点我</a>",
            "<body onload=alert(1)>",
            "<div style=\"background:url('javascript:alert(1)')\">",
            "&#60;script&#62;alert('实体混淆')&#60;/script&#62;",   // HTML 实体绕过后二次解码仍要失活
            "<scr<script>ipt>alert(1)</scr</script>ipt>",            // 嵌套拆分绕过
            "正文 <object data='evil.swf'> 混排"
    })
    @DisplayName("XSS payload 全部失活：无标签残留、无事件属性、无脚本内容进纯文本")
    void xssPayloadsNeutralized(String payload) {
        String text = MarkdownProcessor.extractText("正常开头 " + payload + " 正常结尾");

        assertThat(text)
                .doesNotContain("<", ">", "onerror", "onload", "<script")
                // jsoup text() 不收集 script/style 内容：alert 调用串本身都不该出现在语料里
                .doesNotContain("alert(1)");
        // 无辜文字保留：清洗不是"见 HTML 就整段丢弃"
        assertThat(text).contains("正常开头", "正常结尾");
    }

    @Test
    @DisplayName("实体混淆：解码后的残余尖括号被结构剥离，载荷不可能被二次解析成元素")
    void entityEncodedScriptRemoved() {
        String text = MarkdownProcessor.extractText("&lt;script&gt;alert('entity')&lt;/script&gt;安全文字");
        // jsoup 把实体解码成惰性纯文本（本轮不执行），实现再以"去尖括号"收口二级 XSS：
        // 残余是无害字面量（alert('entity') 作为作者文字保留），但不含任何可复活的结构字符
        assertThat(text).doesNotContain("<", ">").contains("安全文字");
    }

    @Test
    @DisplayName("字数统计：中文按字符、空白不计、代码词计入")
    void wordCountSemantics() {
        // "你好世界" 4 字符 + "hello" 5 字符 = 9；空格/换行不计（编辑量感口径，见方法注释）
        assertThat(MarkdownProcessor.countWords("你好世界 hello")).isEqualTo(9);
        assertThat(MarkdownProcessor.countWords("")).isZero();
        assertThat(MarkdownProcessor.countWords("   \n\t  ")).isZero();
        assertThat(MarkdownProcessor.countWords(null)).isZero();
    }

    @Test
    @DisplayName("围栏代码块不被行内反引号规则错位吞并（FENCED→INLINE 处理次序回归）")
    void fencedBlockWithBacktickInsideNotEaten() {
        String md = """
                前文
                ```
                npm install `pkg`
                ```
                后文
                """;
        String text = MarkdownProcessor.extractText(md);
        assertThat(text).contains("前文", "npm install", "pkg", "后文");
    }

    @Test
    @DisplayName("摘要兜底：超长截断加省略号（M6 AI 降级链复用此方法的前置保障）")
    void fallbackSummaryTruncates() {
        String long200 = "字".repeat(200);
        String s = MarkdownProcessor.fallbackSummary(long200, 120);
        assertThat(s).hasSize(121).endsWith("…");
        assertThat(MarkdownProcessor.fallbackSummary("短文本", 120)).isEqualTo("短文本");
    }
}
