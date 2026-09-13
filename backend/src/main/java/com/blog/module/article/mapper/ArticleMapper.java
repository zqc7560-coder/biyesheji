package com.blog.module.article.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.blog.module.article.entity.Article;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 文章 Mapper（M0 示范模块，完整数据访问在 M1/M3 展开）。
 *
 * <p>规范：单表 CRUD 一律用 BaseMapper + LambdaQueryWrapper（列名由方法引用映射，
 * 编译期防手写出错，也杜绝拼接 SQL 注入面）；只有多表聚合统计（如标签云、归档树、
 * AI 成本日报）才允许新增 XML 手写 SQL——保留可控性的同时把简单场景的成本压到最低。
 *
 * <p>M10 追加的三个"回收站"SQL 是<b>规范的第一类例外（自定义注解 SQL）</b>，理由写在
 * 每个方法上：Article 实体挂了 {@code @TableLogic}，MP 内置的一切 SELECT/UPDATE/DELETE
 * 都会自动追加 {@code AND deleted = 0}——也就是说"查已删行""复活已删行""物理删行"
 * 这三件事<b>用内置 API 根本表达不出来</b>（不是写法别扭，是语义上被拦截器钉死）。
 * 自定义 SQL 不经过逻辑删除拼接，正好是回收站唯一可行的落地通道。
 */
public interface ArticleMapper extends BaseMapper<Article> {

    /**
     * 回收站分页（GET /api/articles/trash，ADMIN）：查 deleted=1 的行。
     *
     * <p>列裁剪刻意不含 content_md / content_text（两个 MEDIUMTEXT）：回收站是治理
     * 视图，读的是"哪篇被删了、谁删的、什么时候"，把正文捞出来等于每页多传输几十 KB
     * 无人消费的数据（与 M3 列表 selectWithoutBody 同一口径）。
     *
     * <p>排序用 updated_at DESC：文章被逻辑删除时 updated_at 随 UPDATE 刷新，
     * 于是"最新进回收站的排最前"，比 published_at（草稿根本没有该列）更贴合治理语义。
     * keyword 参数化进 LIKE（#{} 预编译占位，无注入面）。
     */
    @Select("""
            <script>
            SELECT id, user_id, category_id, title, cover, summary, summary_source,
                   word_count, status, top_flag, allow_comment, view_count,
                   like_count, comment_count, published_at, created_at, updated_at, version
            FROM article
            WHERE deleted = 1
            <if test="keyword != null and keyword != ''">
              AND title LIKE CONCAT('%', #{keyword}, '%')
            </if>
            ORDER BY updated_at DESC, id DESC
            </script>
            """)
    IPage<Article> selectTrashPage(IPage<Article> page, @Param("keyword") String keyword);

    /**
     * 按 id 读取"回收站里的那一行"（deleted=1 的行，内置 selectById 读不到，理由见类注释）。
     * restore 与 purge 都要先拿它做存在性/前置态判定，并读取 category_id/status 结算计数。
     */
    @Select("""
            SELECT id, user_id, category_id, title, cover, summary, summary_source,
                   word_count, status, top_flag, allow_comment, view_count,
                   like_count, comment_count, published_at, created_at, updated_at, version
            FROM article WHERE id = #{id} AND deleted = 1
            """)
    Article selectTrashedById(@Param("id") Long id);

    /**
     * 复活（管理员回收站→恢复）：deleted 由 1 翻回 0。
     *
     * <p>{@code AND deleted = 1} 是幂等护栏：重复点"恢复"第二次影响 0 行，Service
     * 据此回"文章已在库中"，不会把 version 白白 +1、也不会二次结算分类计数
     * （API 文档 §8.4"幂等以当前状态为前置条件"的同族实现）。
     * updated_at 手工刷新：这条 UPDATE 绕过了 MP 的自动填充（自定义 SQL 不触发
     * MetaObjectHandler），漏刷会让"回收站按时间倒序"的次序失真。
     */
    @Update("UPDATE article SET deleted = 0, updated_at = CURRENT_TIMESTAMP WHERE id = #{id} AND deleted = 1")
    int reviveById(@Param("id") Long id);

    /**
     * 彻底删除（UC-U-09"管理员可在回收站彻底删除"）：物理 DELETE。
     *
     * <p>WHERE 带 {@code deleted = 1} 是安全闸门而非形式条件：它让"对未删除文章执行
     * 彻底删除"这条误操作在 SQL 层就不可能命中——即使调用方（Service）的校验被绕过，
     * 最坏结果也只是影响 0 行。回收站外唯一能物理删行的语句必须自带这道锁。
     */
    @Delete("DELETE FROM article WHERE id = #{id} AND deleted = 1")
    int purgeById(@Param("id") Long id);
}
