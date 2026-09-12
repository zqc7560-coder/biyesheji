package com.blog.module.ai.enums;

/**
 * 内置提示词模板（《AI模块设计.md》§4/§5）—— 模板三级加载优先级的最后一级兜底。
 *
 * <p>优先级：ai_config.summary_prompt / tag_prompt（管理员自定义，NULL 则跳过）
 * &gt; 数据库 ai_prompt_template 表（P2 不做） &gt; 本枚举。
 *
 * <p>version 常量参与 Redis 缓存键（ai:{biz}:{hash}:{model}:{promptVer}:{temp}）：
 * 改提示词时必须同步把 v1 升成 v2，否则旧缓存会持续命中、新模板"看起来没生效"——
 * 这是提示词工程最经典的一类 bug，把版本号编进键里从结构上杜绝。
 *
 * <p>模板正文逐字誊抄《AI模块设计.md》§5.1/§5.2 定稿文案（system 段固化角色与
 * 输出契约；用户内容只进 user 段作为"数据"，这是 prompt 注入防线的第一层——
 * 见设计文档 §8：注入最多污染一次建议结果，永远进不了数据库）。
 */
public enum PromptTemplate {

    /** 摘要模板：80~150 字、最多 2 句、禁元话语堆砌、纯文本输出（无 Markdown/引号/前后缀） */
    SUMMARY("ai_summary_v1", """
            你是一名中文技术博客编辑。你的任务是把用户提供的博客文章正文压缩成一段摘要。
            硬性要求：
            1) 摘要长度 80~150 字（中文字符计），最多 2 句；
            2) 忠实原文，禁止编造原文没有的信息、观点、数据；
            3) 不要出现"本文介绍了一种方法"以外的元话语堆砌，不使用 Markdown 标记；
            4) 结尾不加句号以外的标点；
            5) 只输出摘要文本本身，不要任何前后缀、引号、编号、解释。
            若原文为英文，输出中文摘要。"""),

    /** 标签模板：3~6 个、每个 2~8 字、三维度覆盖、强约束输出纯 JSON 数组（无围栏无解释） */
    TAGS("ai_tag_v1", """
            你是一名中文博客的标签管理员。根据文章内容提炼标签。
            规则：
            1) 输出 3~6 个标签，每个 2~8 个字；
            2) 标签覆盖：主题领域、技术栈/实体、内容类型三个维度，避免同义重复（如"Java"与"java编程"）；
            3) 禁止输出标点、空格、emoji、停用词（"分享""总结""笔记"单独不成标签）；
            4) 必须且只能输出一个 JSON 数组，形如 ["SpringBoot","缓存","Redis"]，
               不要输出 Markdown 代码块标记、不要任何解释文字。""");

    /** user 段的变量注入骨架：标题 + 归一化正文（截断后），摘要/标签共用（设计文档 §5.2） */
    private static final String USER_TEMPLATE = """
            【文章标题】%s
            【文章正文】
            %s""";

    /** 模板版本号：参与缓存键；升版必须改这里（v1→v2 即自动失效全部旧缓存） */
    public final String version;
    /** system 段正文：固化编辑角色与输出契约 */
    public final String systemPrompt;

    PromptTemplate(String version, String systemPrompt) {
        this.version = version;
        this.systemPrompt = systemPrompt;
    }

    /**
     * 渲染 user 段。
     *
     * @param title   文章标题（可空，空时用"（无标题）"占位，避免破坏"【】"结构让模型误读）
     * @param content 归一化+截断后的正文纯文本（截断策略在调用方 AiInputNormalizer 实施）
     */
    public String renderUser(String title, String content) {
        String t = (title == null || title.isBlank()) ? "（无标题）" : title.trim();
        return String.format(USER_TEMPLATE, t, content);
    }
}
