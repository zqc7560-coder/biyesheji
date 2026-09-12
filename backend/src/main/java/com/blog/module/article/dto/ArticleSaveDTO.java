package com.blog.module.article.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 文章新建/更新入参（M3）—— 请求体逐字段对齐《API接口设计.md》§4
 * POST/PUT /api/articles 示例。
 *
 * <p>校验口径与服务层的关系（为什么有些约束在这里、有些在 Service）：
 * <ul>
 *   <li>能表达成"字段级、无上下文"的规则一律进 Bean Validation：标题非空且 ≤200
 *       （DDL VARCHAR(200)，超限进库会静默截断或炸 SQL 异常，必须在入口拒掉）、
 *       摘要 ≤500、封面 ≤255、状态/评论开关取值域——这些违反即 400（错误码表"参数"区段），
 *       data 携带字段明细，前端表单可逐行标红；</li>
 *   <li>"分类是否存在（2003）""乐观锁冲突（2004）"这类需要查库判定的语义放
 *       ArticleService——DTO 层拿不到数据库，硬塞会让 DTO 依赖基础设施，
 *       违反《系统设计.md》"Controller/DTO 不写业务"的分层约定。</li>
 * </ul>
 *
 * <p>version 不在这里声明 @NotNull：新建时前端不传（DB 默认 0），更新时 Service
 * 显式校验必填——两种语义共用一个 DTO 是文档契约（同一请求体形状），
 * 差异用注解一刀切反而会把新建接口拦死。
 */
@Data
public class ArticleSaveDTO {

    /** 标题：非空、≤200（article.title VARCHAR(200)） */
    @NotBlank(message = "标题不能为空")
    @Size(max = 200, message = "标题不能超过200字")
    private String title;

    /** 分类 ID：可空（《数据库设计.md》§3.6 允许"未分类"）；非空时 Service 校验存在性→2003 */
    @Min(value = 1, message = "categoryId 非法")
    private Long categoryId;

    /**
     * 标签名列表（按名提交而非按 ID）：编辑器里用户是"敲词"不是"选库"，
     * 不存在的标签由服务端自动补建（§3.8 name 唯一 + 合法化过滤在 AI 侧，M3 手工侧只做去重/截断）。
     * 上限 10 个防灌库。
     */
    @Size(max = 10, message = "标签最多10个")
    private List<@Size(max = 30, message = "单个标签不能超过30字") String> tags;

    /** 封面图相对 URL（文件上传接口先行返回的路径） */
    @Size(max = 255, message = "封面URL过长")
    private String cover;

    /** 摘要：手填或 AI 生成后人工确认的文本，≤500（article.summary VARCHAR(500)） */
    @Size(max = 500, message = "摘要不能超过500字")
    private String summary;

    /** 摘要来源：0 手动 / 1 AI（答辩演示"AI 生成占比统计"的数据位，默认 0） */
    @Min(value = 0, message = "summarySource 仅接受 0/1")
    @Max(value = 1, message = "summarySource 仅接受 0/1")
    private Integer summarySource;

    /** Markdown 原文：非空（DDL NOT NULL）；正文清洗/纯文本抽取/字数统计在 Service 完成 */
    @NotBlank(message = "正文不能为空")
    private String contentMd;

    /** 状态：0 草稿 / 1 发布——新建时"存草稿还是发布"就由这一个字段决定（文档 §4） */
    @Min(value = 0, message = "status 仅接受 0/1/2")
    @Max(value = 2, message = "status 仅接受 0/1/2")
    private Integer status;

    /** 是否允许评论：0/1，null 时 Service 按 DDL 默认 1 处理（M5 评论模块读取） */
    @Min(value = 0, message = "allowComment 仅接受 0/1")
    @Max(value = 1, message = "allowComment 仅接受 0/1")
    private Integer allowComment;

    /**
     * 乐观锁版本号：仅更新（PUT）必填，取详情接口回显值。
     * 多标签页编辑同一文章时，后保存的一方携带旧 version → UPDATE 影响 0 行 → 2004，
     * 前端据此弹"保留本地/加载服务器"选择框（《项目计划.md》D-8 的前端半边）。
     */
    private Integer version;
}
