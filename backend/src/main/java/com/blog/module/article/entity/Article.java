package com.blog.module.article.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 文章实体 —— 字段与《数据库设计.md》§3.6 article 表一一对应（M0 先行落地的示范实体，
 * 其余 14 表实体在 M1 补齐；示范两个插件注解如何生效）。
 *
 * <p>ORM 映射的两个关键点：
 * <ul>
 *   <li>{@code @Version}：MyBatis-Plus 乐观锁插件（见 MyBatisPlusConfig）会把它拼进
 *       UPDATE 的 WHERE 条件，双窗口编辑冲突时影响 0 行，Service 层抛 2004。
 *       这正是 TC-F-04 用例的底层支撑。</li>
 *   <li>{@code @TableLogic}：逻辑删除字段。开启后所有 MP 内置查询自动追加
 *       deleted=0，removeById 变成 UPDATE deleted=1——博客"回收站/审计"语义都靠它，
 *       且与 DDL 的 deleted 公共字段约定闭环。</li>
 * </ul>
 *
 * <p>列名下划线→驼峰由全局配置 map-underscore-to-camel-case 完成，
 * 个别对不上的字段（如 top_flag/topFlag 其实规则内）无需 @TableField，保留一处显式
 * 注解仅作为写法示范。
 */
@Data
@TableName("article")
public class Article {

    /** 主键：数据库自增（《数据库设计.md》§1.2 为课设演示可读性选自增而非雪花） */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 作者，逻辑外键 sys_user.id */
    private Long userId;

    /** 分类，逻辑外键 category.id，允许 null（未分类） */
    private Long categoryId;

    /** 标题（≤200 字，校验在 M3 的 DTO 层做） */
    private String title;

    /** URL 短链，本期默认用 ID，字段预留 */
    private String slug;

    /** 封面图相对 URL */
    private String cover;

    /** 摘要：用户手填或 AI 生成后确认 */
    private String summary;

    /** 摘要来源：0 手动 / 1 AI —— 答辩演示"AI 生成占比统计"的数据基础 */
    @TableField("summary_source")
    private Integer summarySource;

    /** Markdown 原文 */
    private String contentMd;

    /** 清洗后纯文本：供 ngram 全文搜索与 AI 输入（冗余换性能） */
    private String contentText;

    /** 保存时计算的字数 */
    private Integer wordCount;

    /** 状态机：0 草稿 / 1 已发布 / 2 下线（合法流转矩阵在 M3 实现） */
    private Integer status;

    /** 置顶标记 */
    private Integer topFlag;

    /** 是否允许评论（评论模块 M5 读取） */
    private Integer allowComment;

    /** 浏览量：Redis INCR 异步回写，勿在业务里直接 UPDATE */
    private Long viewCount;

    /** 点赞数（P2） */
    private Integer likeCount;

    /** 评论数冗余：审核通过 +1 / 删除 -1 */
    private Integer commentCount;

    /** 首次发布时间：前台列表排序键 */
    private LocalDateTime publishedAt;

    /** 乐观锁版本号 */
    @Version
    private Integer version;

    /** 创建时间：DDL 默认 CURRENT_TIMESTAMP，insert 时省略即可 */
    private LocalDateTime createdAt;

    /** 更新时间：DDL ON UPDATE 自动维护 */
    private LocalDateTime updatedAt;

    /** 逻辑删除标记 */
    @TableLogic
    private Integer deleted;
}
