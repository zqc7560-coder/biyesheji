package com.blog.module.article.service;

import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.safety.Safelist;

import java.util.regex.Pattern;

/**
 * Markdown 正文处理器（M3）—— 《系统设计.md》§3.2 时序图里 MarkdownSanitizer 角色的实现，
 * 承担 WBS 1.4.3 的三件事：XSS 清洗、纯文本抽取（content_text）、字数统计（word_count）。
 *
 * <p>【为什么服务端清洗是第一道防线（NFR-05 双防线）】
 * 前端 markdown-it + DOMPurify 只保护"当前这个诚实的浏览器"。API 是公开面：
 * 任何人都能绕过前端直接 POST 恶意正文入库。如果脏数据进了库，
 * 受害的是所有打开这篇文章的访客（存储型 XSS）——所以入库前必须洗一遍。
 * 清洗目标不是"可渲染 HTML"而是"安全纯文本"：本系统把 Markdown 原文入库、
 * 渲染完全交给前端，服务端只需产出用于全文索引/AI 输入的 content_text，
 * 因此在抽取文本前用 jsoup Safelist.none() 把所有标签与事件属性剥光，
 * 残留的 <script>、onerror、javascript: 协议在纯文本化后自然失活（不解析=不执行）。
 *
 * <p>【jsoup 清洗的行为依据（决定下面的处理顺序）】
 * 1) {@code clean(html, Safelist.none())} 默认把不在白名单的元素<b>连同子树</b>整体丢弃
 *    —— {@code <script>alert(1)</script>} 连调用串一起消失；{@code <img onerror=…>} 的
 *    属性文本根本不会进 text；{@code <scr<script>ipt>} 这类拆分绕过被当畸形标签名吃掉；
 * 2) 输出序列化时文本节点重新转义（{@code &} {@code <} 还原为实体），因此 clean 的输出
 *    再 parse 一次取 text() 才能得到"作者可见文字"（如链接里的"点我"、正文中的"安全文字"）。
 *
 * <p>【二级 XSS（second-order）的最后收口：剥离残余尖括号】HTML 实体
 * （{@code &lt;script&gt;}）经 jsoup 解码后成为"惰性纯文本字符"——本轮不执行，
 * 但它以字面量 {@code <script>} 形态躺在 content_text 里，任何下游消费者
 * （未来的导出器/日志查看器/管理端预览）一旦把它再当 HTML 解析就"复活"。
 * 纯文本索引列里的尖括号几乎不承担语义（代码块围栏已剥、数学比较符罕见），
 * 因此 text() 后直接把 {@code <} {@code >} 抹掉：让 content_text 在结构上
 * 不可能被误解析出元素。content_md 原文不动，渲染安全由前端
 * markdown-it→DOMPurify 第二道防线负责（NFR-05 双防线的分工）。
 *
 * <p>静态工具类：无状态、无依赖注入，Service 与单测都直接调用。
 */
@Slf4j
public final class MarkdownProcessor {

    /**
     * 围栏代码块 ```lang ... ```：抽取纯文本前先把代码块内容"降级"为普通文字。
     * 不删掉整块是因为代码关键词也是有效的搜索/AI 输入语料；
     * 只剥掉反引号围栏语法，避免 ``` 噪声进索引。
     */
    private static final Pattern FENCED_CODE = Pattern.compile("```[\\s\\S]*?```");

    /** 行内代码 `x`：保留内容、剥反引号（同上，反引号是渲染语法不是语料） */
    private static final Pattern INLINE_CODE = Pattern.compile("`([^`]*)`");

    /** 图片 ![alt](url)：整体替换为 alt 文本 —— url 是路径不是语料，alt 才是内容描述 */
    private static final Pattern IMAGE = Pattern.compile("!\\[([^\\]]*)\\]\\([^)]*\\)");

    /** 链接 [text](url)：保留 text 剥 url（同上；url 里的英文单词会污染中文搜索相关性） */
    private static final Pattern LINK = Pattern.compile("\\[([^\\]]*)\\]\\(([^)]*)\\)");

    /** ATX 标题 #...###### 前缀、列表符号、引用符、强调符：行级/字符级语法噪音 */
    private static final Pattern HEADING = Pattern.compile("(?m)^\\s{0,3}#{1,6}\\s*");
    private static final Pattern LIST_MARK = Pattern.compile("(?m)^\\s*([-*+]|\\d+\\.)\\s+");
    private static final Pattern QUOTE = Pattern.compile("(?m)^\\s*>+\\s?");
    /**
     * 强调符：星号/波浪号一律剥（散文里几乎不做词汇成分）；下划线只剥"词边界"处的
     * ——CommonMark 规定 `snake_case` 词内下划线不构成强调，无差别剥除会把
     * 代码/AI 语料里最常见的标识符形态 `content_text` 洗成 `contenttext`，污染索引。
     */
    private static final Pattern EMPHASIS =
            Pattern.compile("[*~]+|(?<!\\w)_{1,3}(?=\\w)|(?<=\\w)_{1,3}(?!\\w)");

    /** 折叠连续空白为单个空格：字数统计与检索语料都不需要排版空白 */
    private static final Pattern BLANKS = Pattern.compile("\\s+");

    /**
     * 提取安全纯文本（写入 article.content_text，供 ngram 全文索引与 AI 摘要输入）。
     *
     * <p>两步式：先去 Markdown 语法噪音（正则，纯性能考量——直接塞给 jsoup 也能出文本，
     * 但 **bold** 的星号、[]() 的括号会进索引）；再用 jsoup clean+parse 处置正文里
     * 可能内嵌的原始 HTML（Markdown 允许混写 HTML 标签，这是 XSS 载荷的主要藏身处）。
     */
    public static String extractText(String markdown) {
        if (markdown == null || markdown.isBlank()) {
            return "";
        }
        String md = markdown;
        // 围栏代码块保留内容：先把成对围栏连根挖出后回灌其内部文本，
        // 防止 ``` 内部出现的 "`" 让 INLINE_CODE 错位吞并大段正文
        md = FENCED_CODE.matcher(md).replaceAll(mr -> {
            String block = mr.group();
            // 去掉首行 ```lang 与结尾 ```，保留中间代码文本
            String inner = block.replaceAll("^```[^\\n]*\\n?", "").replaceAll("\\n?```$", "");
            return java.util.regex.Matcher.quoteReplacement("\n" + inner + "\n");
        });
        md = INLINE_CODE.matcher(md).replaceAll("$1");
        md = IMAGE.matcher(md).replaceAll("$1");
        md = LINK.matcher(md).replaceAll("$1");
        md = HEADING.matcher(md).replaceAll("");
        md = LIST_MARK.matcher(md).replaceAll("");
        md = QUOTE.matcher(md).replaceAll("");
        md = EMPHASIS.matcher(md).replaceAll("");
        md = md.replace("\r\n", "\n");

        // Safelist.none() = 不赦免任何标签：jsoup 先解析再解码实体后剥除全部 HTML 结构，
        // 只留文本节点；script/style 的文本内容 text() 天然跳过（见类注释）
        Document.OutputSettings settings = new Document.OutputSettings().prettyPrint(false);
        String cleaned = Jsoup.clean(md, "", Safelist.none(), settings);
        String text = BLANKS.matcher(Jsoup.parse(cleaned).text()).replaceAll(" ").trim();
        // 结构上不可能被误解析：抹掉残余尖括号（二级 XSS 收口，见类注释）
        return text.replace('<', ' ').replace('>', ' ').trim();
    }

    /**
     * 统计字数：中文按字符计、英文按词计之外的通用近似 —— 对归一化纯文本
     * 去除空白后计 Unicode 码点数。
     *
     * <p>为什么不逐字母数（"abc" 记 1 还是 3 有争议）：word_count 的业务用途是
     * "编辑页面的写作量感反馈 + 阅读时长估算"，微信/语雀口径都是
     * 非空白字符计数，简单且与用户直觉一致；精确性不是本字段的诉求
     * （它不参与任何计费或校验）。
     */
    public static int countWords(String plainText) {
        if (plainText == null || plainText.isBlank()) {
            return 0;
        }
        return (int) BLANKS.matcher(plainText).replaceAll("").codePoints().count();
    }

    /**
     * 摘要兜底：从纯文本截取前 maxLen 个字符作手动摘要缺失时的占位。
     * M6 的"AI 失败→机械摘要降级"会复用它（《AI模块设计.md》§4 兜底链），
     * 因此放在这里而不是 ArticleService 私有方法。
     */
    public static String fallbackSummary(String plainText, int maxLen) {
        if (plainText == null || plainText.isBlank()) {
            return "";
        }
        String oneLine = plainText.length() > maxLen ? plainText.substring(0, maxLen) + "…" : plainText;
        return oneLine;
    }

    private MarkdownProcessor() {
    }
}
