package com.blog.module.article.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.blog.module.article.entity.Article;

/**
 * 文章 Mapper（M0 示范模块，完整数据访问在 M1/M3 展开）。
 *
 * <p>规范：单表 CRUD 一律用 BaseMapper + LambdaQueryWrapper（列名由方法引用映射，
 * 编译期防手写出错，也杜绝拼接 SQL 注入面）；只有多表聚合统计（如标签云、归档树、
 * AI 成本日报）才允许新增 XML 手写 SQL——保留可控性的同时把简单场景的成本压到最低。
 */
public interface ArticleMapper extends BaseMapper<Article> {
}
