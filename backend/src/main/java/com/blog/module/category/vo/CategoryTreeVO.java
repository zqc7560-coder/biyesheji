package com.blog.module.category.vo;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 分类树节点 VO（M4）—— GET /api/categories 返回"分类树（含 article_count）"。
 *
 * <p>为什么前端拿树而不是平铺列表：公开导航（UC-V-06 分类导航）需要两级结构一次取全，
 * 服务端组装比前端 reduce 省一次心智负担；children 用 ArrayList 而非 List.of，
 * Service 在装配时直接 add。articleCount 直接回冗余列（保存文章事务内维护，
 * 分类页展示够用；治理场景的"严格占用数"是删除守卫内部另查的 countLiveArticles）。
 */
@Data
public class CategoryTreeVO {

    /** 分类 id（前端筛选参数 categoryId 即此值） */
    private Long id;

    /** 分类名 */
    private String name;

    /** URL 别名（本期仅存储展示，静态化路由随 M12 视需要启用） */
    private String slug;

    /** 父 id：0=顶级（前端两列导航渲染需要） */
    private Long parentId;

    /** 导航排序 */
    private Integer sortOrder;

    /** 描述 */
    private String description;

    /** 冗余文章数（仅已发布口径） */
    private Integer articleCount;

    /** 子分类（顶级节点下挂二级；二级节点 children 恒为空数组而非 null，前端免判空） */
    private List<CategoryTreeVO> children = new ArrayList<>();
}
