package com.blog.module.category.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 分类新增/修改请求体（M4）—— 对齐《API接口设计.md》§5：POST {name,parentId,sortOrder}。
 *
 * <p>校验注解与设计表结构逐列对齐（《数据库设计.md》§3.7）：name VARCHAR(50) 唯一、
 * slug VARCHAR(60)、description VARCHAR(200)、parent_id 默认 0（顶级）。
 * 新建与修改共用一个 DTO：修改时字段为 null 表示"不变更"（Service 用 MP
 * updateById 的 null-skip 语义实现局部更新，前端不用回传全量）。
 */
@Data
public class CategorySaveDTO {

    /** 分类名：非空、≤50 字（uk_name 唯一性走数据库兜底 + Service 查重回 1103 风格文案） */
    @NotBlank(message = "分类名不能为空")
    @Size(max = 50, message = "分类名不能超过50字")
    private String name;

    /** URL 别名：可选，留空则 Service 生成占位（本期导航按 id 过滤，slug 只是预留字段） */
    @Size(max = 60, message = "别名不能超过60字")
    private String slug;

    /** 父分类：0/缺省=顶级；本期两级封顶，Service 校验父级存在且自身不是二级 */
    @Min(value = 0, message = "parentId 非法")
    private Long parentId;

    /** 排序：越小越靠前；新建缺省 0，修改时 null=不变更 */
    @Min(value = 0, message = "sortOrder 不能为负")
    private Integer sortOrder;

    /** 描述：可选，≤200 字（VARCHAR(200)） */
    @Size(max = 200, message = "描述不能超过200字")
    private String description;
}
