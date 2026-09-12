package com.blog.module.article.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.blog.module.article.entity.ArticleTag;

/**
 * 文章标签关联 Mapper。
 *
 * <p>M4 台账坑（写给下一轮）：曾在这里给"合并标签"写过 INSERT IGNORE ... SELECT
 * 注解 SQL，结果被 M0 装配的防全表攻击插件（BlockAttackInnerInterceptor）在
 * beforePrepare 阶段用 JSqlParser 解析时抛 UnsupportedOperationException
 * （该插件不支持解析 INSERT-SELECT 形态）。合并逻辑最终收进 TagService 用
 * "集合差 + 逐行 insert"实现——博客量级下关联搬移是几十行级别，纯 Java 足够，
 * 还顺带免疫了 MySQL/H2 的 INSERT IGNORE 方言差异。
 */
public interface ArticleTagMapper extends BaseMapper<ArticleTag> {
}
