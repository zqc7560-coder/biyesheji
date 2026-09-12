package com.blog.module.article.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 文章出参 VO（M3）—— 同时承载"列表项（薄）"与"详情（厚）"两种形状，
 * 字段集合对齐《API接口设计.md》§4 的两段响应示例。
 *
 * <p>类级 {@code @JsonInclude(NON_NULL)} 的动机：
 * 列表查询绝不 SELECT content_md/content_text（MEDIUMTEXT 大字段，一页 10 篇
 * 就是几十 KB 的无谓传输），因此列表场景下正文相关字段为 null——若不隐去，
 * 前端会拿到 "contentMd": null 误判"正文为空"，且列表载荷膨胀。
 * NON_NULL 让"字段缺省"回归 JSON 语义本身：没查的字段不出现在响应里。
 *
 * <p>为什么不拆成 ArticleBriefVO / ArticleDetailVO 两个类：两形状 90% 字段重合，
 * 拆开就是两份要同步维护的字段清单；文档契约里它们本来就是同一资源的两种视图
 * （列表 = 详情的投影），一个 Builder 类 + 场景化装配方法更省。
 */
@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
// M9 缓存回环说明（含两次实测修正，均由 ArticleCacheRoundTripM9Test 钉死）：
// 详情缓存 blog:article:{id} 要把本 VO 序列化进 Redis 再读回来，反序列化对
// 构造器的要求就两条路，前两条都被堵死过：
//  ① @JsonDeserialize(builder=ArticleVOBuilder.class) —— 失败：Lombok builder
//     的链式方法不被 Jackson 默认内省识别（"0 known properties"，静默回吐空对象，
//     NON_NULL 下接口层表现为 data={}——命中路径整体丢字段）；
//  ② 裸 @Data @Builder —— 失败：类上一旦有 @Builder，Lombok 不再合成无参构造
//     （只留 builder 用的全参构造），Jackson "no Creators" 直接抛 InvalidDefinition。
// 正解 = ③ 显式 @NoArgsConstructor（给 Jackson 建对象）+ @AllArgsConstructor
// （把 @Builder 藏起来的那个全参构造恢复成 public，字段赋值语义不变）。
@lombok.NoArgsConstructor
@lombok.AllArgsConstructor
public class ArticleVO {

    /** 文章 ID */
    private Long id;

    /** 标题 */
    private String title;

    /** 封面图 URL */
    private String cover;

    /** 摘要（列表页卡片直接展示，无需进详情） */
    private String summary;

    /** 摘要来源：0 手动 / 1 AI —— 前端据此在摘要旁打 "AI" 角标（UC-U-05 演示点） */
    private Integer summarySource;

    /** Markdown 原文：仅详情返回（编辑器回填、前端 markdown-it 渲染共用一份，保证"编辑所见=发布所存"） */
    private String contentMd;

    /** 作者摘要信息：详情/归档返回（详情页署名区） */
    private AuthorBrief author;

    /** 分类摘要信息 */
    private CategoryBrief category;

    /** 标签列表 */
    private List<TagBrief> tags;

    /** 浏览量（Redis 计数回写口径，读接口允许分钟级滞后——需求分析规则 2 的取舍） */
    private Long viewCount;

    /** 点赞数 */
    private Integer likeCount;

    /** 评论数冗余 */
    private Integer commentCount;

    /** 状态：0 草稿 / 1 已发布 / 2 下线 —— 作者后台列表据此渲染标签色 */
    private Integer status;

    /** 是否允许评论（详情页据此决定评论区组件挂载，M5 消费） */
    private Integer allowComment;

    /** 首次发布时间：前台列表排序键与详情页时间线展示 */
    private LocalDateTime publishedAt;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 更新时间：编辑页"最后保存于…"提示 */
    private LocalDateTime updatedAt;

    /** 乐观锁版本号：详情回显，PUT 保存时原样携带回传（防覆盖闭环的数据半边） */
    private Integer version;

    /** 作者ID：列表薄对象也带上（前端"编辑"按钮的可见性判断：userId===me.id 才显示） */
    private Long userId;

    /** 嵌套：作者公开信息（三件套，不暴露邮箱等 PII——文章页读者不需要作者联系方式） */
    @Data
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    // M9：缓存回环需要（论证同外层 ArticleVO）
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class AuthorBrief {
        private Long id;
        private String nickname;
        private String avatar;
    }

    /** 嵌套：分类 */
    @Data
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    // M9：缓存回环需要（论证同外层 ArticleVO）
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class CategoryBrief {
        private Long id;
        private String name;
    }

    /** 嵌套：标签 */
    @Data
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    // M9：缓存回环需要（论证同外层 ArticleVO）
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class TagBrief {
        private Long id;
        private String name;
    }
}
