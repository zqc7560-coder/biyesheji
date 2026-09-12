package com.blog.module.tag.vo;

import lombok.Data;

/**
 * 标签 VO（M4）—— GET /api/tags 列表项；?cloud=true 时附加热度权重。
 *
 * <p>为什么不直接回 Tag 实体：实体带 createdAt/updatedAt/deleted 等治理字段，
 * 公开接口按防腐层约定回白名单 VO（M0 起的原则）；权重字段是纯计算派生值，
 * 放 VO 而不污染表实体。
 */
@Data
public class TagVO {

    /** 标签 id（前端筛选参数 tagId 即此值） */
    private Long id;

    /** 标签名 */
    private String name;

    /** 展示色 */
    private String color;

    /** 使用数（M3 起对全部未删除文章计数：标签是文章属性而非发布态属性，草稿也占位） */
    private Integer articleCount;

    /**
     * 标签云权重 [1,5]：仅 ?cloud=true 时填充（非云场景为 null，省得前端猜字段来源）。
     * 计算口径：线性归一 count→[1,5]；全零（空库/冷启动）时统一回中位 3，
     * 避免除零与"所有标签一样灰"的难看渲染。
     */
    private Double weight;
}
