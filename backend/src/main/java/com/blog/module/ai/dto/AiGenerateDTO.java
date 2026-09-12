package com.blog.module.ai.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * AI 生成请求 DTO（POST /api/ai/summary 与 /api/ai/tags 共用）。
 *
 * <p>两接口请求体本来就同构（《API接口设计.md》§7：tags 仅少了 style 字段），
 * 共用一个 DTO 少一套校验。
 *
 * <p>为什么允许直接收 contentMd 而不是 articleId：编辑器的使用场景是"文章还没保存
 * （id 尚不存在）就想生成摘要/标签"（§10 演示脚本步骤 2），所以正文必须随请求传输。
 * 这决定了服务端必须把正文当**不可信输入**处理：归一化+截断（控成本）、
 * 不注入任何用户标识进 prompt（防跨用户泄露）、结果只回显不落库（保存由用户确认）。
 */
@Data
public class AiGenerateDTO {

    /** 文章正文（Markdown）：服务端去标记→截断→哈希→进 user 段 */
    @NotBlank(message = "正文不能为空")
    private String contentMd;

    /** 标题：可选但强烈建议传（提示词模板里是独立段，显著提高相关性） */
    @Size(max = 200)
    private String title;

    /** 预留样式参数（如 zh）：当前仅接受不影响输出，字段先对齐文档契约 */
    @Size(max = 20)
    private String style;
}
