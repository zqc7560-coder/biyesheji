package com.blog.module.category.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.blog.module.category.entity.Category;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 分类 Mapper（M4 补齐治理所需的跨表统计）。
 *
 * <p>为什么用 @Select 注解 SQL 而非 LambdaQueryWrapper："能不能删分类"要数
 * **所有未删除文章**（含草稿/下线），是跨表 COUNT，MP 单表 wrapper 表达不了；
 * deleted=0 显式手写，语义透明（与 M3 列表 tagId 子查询同一策略）。
 */
public interface CategoryMapper extends BaseMapper<Category> {

    /**
     * 分类下的"占用文章数"：deleted=0 的全部文章（草稿/已发布/下线都算）。
     * 删除守卫用——比冗余列 article_count（只数已发布）更严格：
     * 按冗余列放行会把草稿变成指向已删分类的孤儿引用。
     */
    @Select("SELECT COUNT(*) FROM article WHERE deleted = 0 AND category_id = #{categoryId}")
    long countLiveArticles(@Param("categoryId") Long categoryId);
}
