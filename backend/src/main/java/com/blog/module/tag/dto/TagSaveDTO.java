package com.blog.module.tag.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 标签新增/修改请求体（M4）—— 对齐《API接口设计.md》§5：POST {name,color}。
 *
 * <p>name 的合法化收口在 DTO 层先行一道（trim 后非空、≤30 字、禁空白字符），
 * M7 的 AI 标签"合法化过滤"（去重/黑名单词）在服务层做——两道闸各管一头：
 * 这里是人手工输入的格式，那里是模型输出的卫生。
 */
@Data
public class TagSaveDTO {

    /** 标签名：≤30 字（tag.name VARCHAR(30)）；\S 校验禁止内部空白，避免"AI 后端"与"AI  后端"并存 */
    @NotBlank(message = "标签名不能为空")
    @Size(max = 30, message = "标签名不能超过30字")
    @Pattern(regexp = "\\S+", message = "标签名不能包含空白字符")
    private String name;

    /** 展示色：十六进制色值（#409EFF / #abc123），直接进内联 style，故必须白名单格式防 CSS 注入 */
    @Pattern(regexp = "^#([0-9a-fA-F]{3}|[0-9a-fA-F]{6})$", message = "color 必须是 #RGB 或 #RRGGBB 十六进制色值")
    private String color;

    /** 合并标签请求（POST /api/tags/merge {fromTagId,toTagId}）；与新增共用 DTO 类文件、独立内部类，避免再开一个只有两字段的文件 */
    @Data
    public static class MergeRequest {

        /** 被合并方（合并成功后逻辑删除） */
        @NotNull(message = "fromTagId 不能为空")
        private Long fromTagId;

        /** 目标方（保留，接管 from 的全部文章关联） */
        @NotNull(message = "toTagId 不能为空")
        private Long toTagId;
    }
}
