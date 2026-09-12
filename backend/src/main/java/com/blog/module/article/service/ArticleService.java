package com.blog.module.article.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.blog.common.BizCode;
import com.blog.common.BizException;
import com.blog.common.PageResult;
import com.blog.module.article.dto.ArticleSaveDTO;
import com.blog.module.article.entity.Article;
import com.blog.module.article.entity.ArticleTag;
import com.blog.module.article.mapper.ArticleMapper;
import com.blog.module.article.mapper.ArticleTagMapper;
import com.blog.module.article.vo.ArticleVO;
import com.blog.module.category.entity.Category;
import com.blog.module.category.mapper.CategoryMapper;
import com.blog.module.tag.entity.Tag;
import com.blog.module.tag.mapper.TagMapper;
import com.blog.module.user.entity.SysUser;
import com.blog.module.user.mapper.SysUserMapper;
import com.blog.security.LoginUser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 文章核心服务（M3）—— 状态机 / CRUD / Markdown 处理 / 标签关联重建 / 可见性矩阵。
 *
 * <p>覆盖用例：UC-U-04 撰写、UC-U-07 草稿、UC-U-08 发布/下线/回草稿、
 * UC-U-09 编辑/删除（《需求分析.md》§2.2），实现骨架对齐《系统设计.md》§3.2 时序图
 * （不含 Redis 缓存失效那一步——缓存体系在 M9 整体接入，避免本轮为半成品缓存写双套代码）。
 *
 * <p>三条贯穿全类的硬规则：
 * <ol>
 *   <li><b>可见性矩阵集中一处</b>（{@link #assertReadable}）：草稿/下线仅作者与 ADMIN
 *       可见（需求分析规则 1"仅已发布对外可见"）。散落在各查询里的 if 迟早漏一处，
 *       集中判定 + 越权矩阵单测锁死（《部署与测试.md》§4）。</li>
 *   <li><b>属主校验用 WHERE id AND user_id</b>（越权三防第三层）：更新/删除类写操作
 *       的影响行数把"资源不存在"与"存在但不是你的"折叠成同一结果，
 *       因此先 selectById 判存在→2001，再比属主→2002，两码分开（错误码表如此约定，
 *       探测成本是多一次主键查询，可接受）。</li>
 *   <li><b>published_at 只在"首次 0→1"时写</b>：下线再上线保持原始时间
 *       （归档列表按首发时间归位；否则文章会"漂"到最新页，也给了刷排序的空子）。</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ArticleService {

    private final ArticleMapper articleMapper;
    private final ArticleTagMapper articleTagMapper;
    private final CategoryMapper categoryMapper;
    private final TagMapper tagMapper;
    private final SysUserMapper userMapper;

    /** 状态常量（DDL 注释：0 草稿 / 1 已发布 / 2 下线）——用常量代替魔法数字，状态机矩阵可读 */
    public static final int STATUS_DRAFT = 0;
    public static final int STATUS_PUBLISHED = 1;
    public static final int STATUS_OFFLINE = 2;

    /**
     * 状态流转矩阵（需求分析规则 1）：草稿→发布、发布⇄下线；
     * 禁止 0→2（没发布过的东西不存在"下线"语义）与自转（幂等场景直接返回，不进矩阵）。
     * key=当前状态，value=允许的目标状态集。
     */
    private static final Map<Integer, Set<Integer>> TRANSITIONS = Map.of(
            STATUS_DRAFT, Set.of(STATUS_PUBLISHED),
            STATUS_PUBLISHED, Set.of(STATUS_DRAFT, STATUS_OFFLINE),
            STATUS_OFFLINE, Set.of(STATUS_PUBLISHED));

    // ==================================================================
    // 查询
    // ==================================================================

    /**
     * 前台/后台分页列表（GET /api/articles）。
     *
     * <p>参数语义（《API接口设计.md》§4）：
     * status 仅作者后台生效——前台永远只看 status=1；
     * mine=true 要求登录且强制 author=me（我的文章页）；
     * 排序默认 published_at desc，置顶（top_flag）恒先。
     *
     * <p>关键词搜索本期用 LIKE 而非 ngram MATCH：H2 测试库没有 ngram 全文索引，
     * 且 1 万篇以下 LIKE 全表在 200 并发读下有缓存层（M9 blog:list:*）兜底；
     * 生产索引已建好，切 MATCH AGAINST 只是本方法内一行 SQL（风险 R-09 的降级预案
     * 反过来就是：先跑通 LIKE，压测不达标再升级 FTS，避免为未验证的性能预付复杂度）。
     */
    public PageResult<ArticleVO> pageArticles(ArticleQuery query) {
        LambdaQueryWrapper<Article> qw = selectWithoutBody();

        if (query.mine) {
            // 我的文章：默认不限状态（草稿箱语义），UC-U-12 要求"按状态筛选"，
            // status 传入时在本分支内生效——注意不能复用 adminScope 的 status 逻辑，
            // 那会绕开"只看自己的"约束的前提是 userId 等值条件必须仍在
            qw.eq(Article::getUserId, query.currentUserId);
            if (query.status != null) {
                qw.eq(Article::getStatus, query.status);
            }
        } else {
            // 公开列表：非管理员永远只能看"已发布"；ADMIN 可按 status 筛全站
            if (query.adminScope) {
                if (query.status != null) {
                    qw.eq(Article::getStatus, query.status);
                }
                if (query.authorId != null) {
                    qw.eq(Article::getUserId, query.authorId);
                }
            } else {
                qw.eq(Article::getStatus, STATUS_PUBLISHED);
            }
        }
        if (query.categoryId != null) {
            qw.eq(Article::getCategoryId, query.categoryId);
        }
        // tagId 过滤：article_tag 是中间表，MP 单表 wrapper 表达不了 JOIN，
        // 用 inSql 子查询（参数是 Long，无注入面；比先查关联表拿 id 清单少一次往返）
        if (query.tagId != null) {
            qw.inSql(Article::getId,
                    "SELECT article_id FROM article_tag WHERE tag_id = " + query.tagId);
        }
        if (query.keyword != null && !query.keyword.isBlank()) {
            String kw = query.keyword.trim();
            // 两列 LIKE 用 and() 括起：OR 不加括号会把 status 条件短路（布尔优先级事故）
            qw.and(w -> w.like(Article::getTitle, kw).or().like(Article::getContentText, kw));
        }

        // 排序：置顶恒优先，其后按请求指定（白名单映射，防 order by 注入）
        qw.orderByDesc(Article::getTopFlag);
        applySort(qw, query.sort);
        qw.orderByDesc(Article::getId); // 同值时的稳定次序，翻页不跳动

        Page<Article> page = articleMapper.selectPage(
                new Page<>(query.pageNum, query.pageSize),
                qw); // 注意：必须传带条件的 qw 本体（此前误传新建的 selectWithoutBody() 把条件整体丢弃，全量泄库——列裁剪已在方法开头合并进 qw）

        List<ArticleVO> vos = toBriefVOList(page.getRecords());
        return new PageResult<>(page.getTotal(), page.getPages(),
                page.getCurrent(), page.getSize(), vos);
    }

    /**
     * 详情（GET /api/articles/{id}）：可见性判定 + 作者/分类/标签聚合。
     *
     * <p>pv+1 本期不做（设计文档把浏览计数归到 Redis blog:pv:{id} + 定时回写，
     * 那是 M9 缓存体系的一部分；在纯 DB 上每读 UPDATE view_count 会把读接口写成
     * 写接口，压测 200 并发直接自伤——台账里给 M9 留了钩子说明）。
     */
    public ArticleVO detail(Long id, LoginUser me) {
        Article article = articleMapper.selectById(id);
        if (article == null) {
            throw new BizException(BizCode.ARTICLE_NOT_FOUND, BizCode.ARTICLE_NOT_FOUND.getMessage(), 404);
        }
        assertReadable(article, me);
        return assembleDetail(article);
    }

    /**
     * 归档树（GET /api/articles/archive）：{"2026": {"09": [{id,title,publishedAt}]}}。
     *
     * <p>实现选择：一次查全部已发布文章的 (id,title,published_at) 三列
     * （不 SELECT 正文），Java 端 group——博客归档是低频只读页，万级行内存分组
     * 微秒级完成，省去 MySQL 日期函数在 H2/MySQL 的方言差异（YEAR()/MONTH()
     * 在两库行为一致但格式化函数不一致，能在 Service 端消灭的差异就不留给 DB）。
     */
    public Map<String, Map<String, List<ArticleVO>>> archive() {
        List<Article> rows = articleMapper.selectList(new LambdaQueryWrapper<Article>()
                .select(Article::getId, Article::getTitle, Article::getPublishedAt)
                .eq(Article::getStatus, STATUS_PUBLISHED)
                .isNotNull(Article::getPublishedAt)
                .orderByDesc(Article::getPublishedAt));

        Map<String, Map<String, List<ArticleVO>>> tree = new java.util.LinkedHashMap<>();
        for (Article a : rows) {
            String year = String.valueOf(a.getPublishedAt().getYear());
            String month = String.format("%02d", a.getPublishedAt().getMonthValue());
            tree.computeIfAbsent(year, k -> new java.util.LinkedHashMap<>())
                    .computeIfAbsent(month, k -> new ArrayList<>())
                    .add(ArticleVO.builder().id(a.getId()).title(a.getTitle())
                            .publishedAt(a.getPublishedAt()).build());
        }
        return tree;
    }

    /** 某作者的公开文章（GET /api/users/{id}/articles，用户主页用；只回已发布） */
    public PageResult<ArticleVO> authorArticles(Long authorId, long pageNum, long pageSize) {
        Page<Article> page = articleMapper.selectPage(new Page<>(pageNum, pageSize),
                new LambdaQueryWrapper<Article>()
                        .select(Article::getId, Article::getUserId, Article::getTitle,
                                Article::getSummary, Article::getCover, Article::getSummarySource,
                                Article::getViewCount, Article::getLikeCount, Article::getCommentCount,
                                Article::getStatus, Article::getPublishedAt)
                        .eq(Article::getUserId, authorId)
                        .eq(Article::getStatus, STATUS_PUBLISHED)
                        .orderByDesc(Article::getTopFlag)
                        .orderByDesc(Article::getPublishedAt));
        return new PageResult<>(page.getTotal(), page.getPages(), page.getCurrent(), page.getSize(),
                toBriefVOList(page.getRecords()));
    }

    // ==================================================================
    // 写入
    // ==================================================================

    /**
     * 新建文章（POST /api/articles）：草稿与发布共用一个入口（status 字段决定，文档 §4）。
     *
     * @return {id, wordCount} —— wordCount 回显让编辑器"保存成功"提示顺带显示字数
     */
    @Transactional
    public Map<String, Object> create(LoginUser me, ArticleSaveDTO dto) {
        int status = dto.getStatus() == null ? STATUS_DRAFT : dto.getStatus();
        // 新建入口只允许 0/1：下线是"曾经发布过"的状态，凭空新建 status=2 无业务语义
        if (status != STATUS_DRAFT && status != STATUS_PUBLISHED) {
            throw new BizException(BizCode.PARAM_ERROR, "新建文章状态仅支持草稿(0)或发布(1)");
        }
        validateCategory(dto.getCategoryId());

        Article article = new Article();
        article.setUserId(me.getUserId());
        applyEditableFields(article, dto);
        article.setStatus(status);
        article.setTopFlag(0);
        article.setAllowComment(dto.getAllowComment() == null ? 1 : dto.getAllowComment());
        article.setViewCount(0L);
        article.setLikeCount(0);
        article.setCommentCount(0);
        article.setVersion(0);
        article.setPublishedAt(status == STATUS_PUBLISHED ? LocalDateTime.now() : null);

        LocalDateTime now = LocalDateTime.now();
        article.setCreatedAt(now);
        article.setUpdatedAt(now);
        articleMapper.insert(article);

        rebuildTags(article.getId(), dto.getTags());
        // 发布态文章计入分类冗余计数（§3.7"保存文章时维护"——草稿不计，分类页只展示发布态）
        adjustCategoryCount(article.getCategoryId(), +1, status);
        log.info("用户 {} 新建文章 id={} status={}", me.getUserId(), article.getId(), status);
        return Map.of("id", article.getId(), "wordCount", article.getWordCount());
    }

    /**
     * 更新文章（PUT /api/articles/{id}）：属主校验 + 乐观锁 + 状态机副作用。
     *
     * <p>乐观锁的落地方式（MP @Version 插件）：把客户端回传的 version 放进实体，
     * updateById 自动拼 "SET version=version+1 WHERE id=? AND version=旧值 AND deleted=0"，
     * 影响 0 行即冲突 → 2004（《项目计划.md》D-8）。之所以不自己手写 WHERE version：
     * 插件语义与 M0 骨架声明一致，两处实现会漂移。
     *
     * <p>状态副作用：更新请求里 status 若从 0/2 变为 1，同步补 published_at（仅首次）——
     * 编辑页"保存并发布"是一个动作，不该让前端再补一发 PATCH。
     */
    @Transactional
    public Map<String, Object> update(LoginUser me, Long id, ArticleSaveDTO dto) {
        Article existing = requireOwned(me, id, "无权编辑他人文章");
        if (dto.getVersion() == null) {
            throw new BizException(BizCode.PARAM_ERROR, "更新必须携带 version（先取详情再保存）");
        }
        validateCategory(dto.getCategoryId());

        Article patch = new Article(); // 只 set 提交的列：MP updateById 默认忽略 null，实现 PATCH 语义
        patch.setId(id);
        patch.setVersion(dto.getVersion());
        applyEditableFields(patch, dto);
        if (dto.getAllowComment() != null) {
            patch.setAllowComment(dto.getAllowComment());
        }

        if (dto.getStatus() != null && !dto.getStatus().equals(existing.getStatus())) {
            assertTransition(existing.getStatus(), dto.getStatus());
            patch.setStatus(dto.getStatus());
            if (dto.getStatus() == STATUS_PUBLISHED && existing.getPublishedAt() == null) {
                patch.setPublishedAt(LocalDateTime.now());
            }
        }
        patch.setUpdatedAt(LocalDateTime.now());

        int affected = articleMapper.updateById(patch);
        if (affected == 0) {
            // 影响 0 行在"已确认存在且属主"前提下只剩一种解释：version 不匹配
            throw new BizException(BizCode.VERSION_CONFLICT);
        }
        if (dto.getTags() != null) {
            rebuildTags(id, dto.getTags());
        }
        // 分类冗余计数在"发布态迁移"时结算（§3.7）：只在旧/新可见性发生
        // 已发布↔非已发布 跨界、或发布态下换分类时才 +/-，草稿间挪分类不动计数。
        // 注意 MP updateById 对 null 列不更新 → DTO 不传 categoryId 语义是"分类不变"
        // （清空分类是 M4 分类治理页的显式动作，本期不做"隐式清空"），
        // 计数结算必须用 effectiveCat 而不是 patch 里的 null，否则会出现"库里分类没动、
        // 计数却 -1"的账实不符。
        Long effectiveCat = dto.getCategoryId() != null ? dto.getCategoryId() : existing.getCategoryId();
        int oldVisible = existing.getStatus() == STATUS_PUBLISHED ? 1 : 0;
        int newStatus = dto.getStatus() != null ? dto.getStatus() : existing.getStatus();
        int newVisible = newStatus == STATUS_PUBLISHED ? 1 : 0;
        if (oldVisible == 0 && newVisible == 1) {
            adjustCategoryCount(effectiveCat, +1, STATUS_PUBLISHED);
        } else if (oldVisible == 1 && newVisible == 0) {
            adjustCategoryCount(existing.getCategoryId(), -1, STATUS_PUBLISHED);
        } else if (oldVisible == 1 && newVisible == 1
                && !java.util.Objects.equals(existing.getCategoryId(), effectiveCat)) {
            adjustCategoryCount(existing.getCategoryId(), -1, STATUS_PUBLISHED);
            adjustCategoryCount(effectiveCat, +1, STATUS_PUBLISHED);
        }
        Article reloaded = articleMapper.selectById(id);
        return Map.of("id", id, "wordCount", reloaded.getWordCount(), "version", reloaded.getVersion());
    }

    /**
     * 状态流转（PATCH /api/articles/{id}/status）：发布/下线/回草稿。
     *
     * <p>幂等（API 文档 §8.4）：目标状态==当前状态直接成功返回，不做无谓 UPDATE
     * （也避免 version 被白白 +1 让并发编辑页频繁撞 2004）。
     * 消息文案按目标状态给（"已发布"/"已下线"/"已回到草稿"），前端 toast 直接可用。
     */
    @Transactional
    public String changeStatus(LoginUser me, Long id, Integer target) {
        Article existing = requireOwned(me, id, "无权操作他人文章");
        if (target.equals(existing.getStatus())) {
            return switch (target) {
                case STATUS_PUBLISHED -> "文章已是发布状态";
                case STATUS_OFFLINE -> "文章已是下线状态";
                default -> "文章已是草稿状态";
            };
        }
        assertTransition(existing.getStatus(), target);

        Article patch = new Article();
        patch.setId(id);
        patch.setVersion(existing.getVersion()); // 以库内当前版本流转：状态动作不需要客户端携带版本（非编辑冲突场景）
        patch.setStatus(target);
        patch.setUpdatedAt(LocalDateTime.now());
        if (target == STATUS_PUBLISHED && existing.getPublishedAt() == null) {
            patch.setPublishedAt(LocalDateTime.now());
        }
        int affected = articleMapper.updateById(patch);
        if (affected == 0) {
            throw new BizException(BizCode.VERSION_CONFLICT); // 与并发编辑相撞：让调用方刷新重试
        }
        // 分类冗余计数随可见性迁移结算：发布 +1、离开发布态 -1（§3.7 维护口径与 update() 一致）
        if (existing.getStatus() != STATUS_PUBLISHED && target == STATUS_PUBLISHED) {
            adjustCategoryCount(existing.getCategoryId(), +1, STATUS_PUBLISHED);
        } else if (existing.getStatus() == STATUS_PUBLISHED && target != STATUS_PUBLISHED) {
            adjustCategoryCount(existing.getCategoryId(), -1, STATUS_PUBLISHED);
        }
        return switch (target) {
            case STATUS_PUBLISHED -> "已发布";
            case STATUS_OFFLINE -> "已下线";
            default -> "已回到草稿";
        };
    }

    /**
     * 逻辑删除（DELETE /api/articles/{id}）：@TableLogic 使 removeById 变 UPDATE deleted=1。
     * 同步清理 article_tag 关联与分类/标签冗余计数——冗余列的正确性必须与主表同事务维护，
     * 否则"文章没了但分类计数还算着它"，分类页会出现点进去 404 的空洞。
     */
    @Transactional
    public void delete(LoginUser me, Long id) {
        Article existing = requireOwned(me, id, "无权删除他人文章");

        articleMapper.deleteById(id);
        adjustCategoryCount(existing.getCategoryId(), -1, existing.getStatus());
        List<Long> tagIds = articleTagMapper.selectList(new LambdaQueryWrapper<ArticleTag>()
                        .eq(ArticleTag::getArticleId, id))
                .stream().map(ArticleTag::getTagId).toList();
        articleTagMapper.delete(new LambdaQueryWrapper<ArticleTag>().eq(ArticleTag::getArticleId, id));
        for (Long tagId : tagIds) {
            bumpTagCount(tagId, -1);
        }
        log.info("用户 {} 删除文章 {}", me.getUserId(), id);
    }

    /**
     * 管理员强制下线（UC-A-02 的一部分；管理端列表复用 pageArticles(adminScope)）。
     * 与作者自服的下线区别仅在入口鉴权（@PreAuthorize article:save + ADMIN 已由
     * Controller 层挡），数据动作同走状态机，护栏是矩阵本身（发布→下线合法，草稿不能被"下线"）。
     */
    @Transactional
    public String forceOfflineByAdmin(Long id) {
        Article existing = articleMapper.selectById(id);
        if (existing == null) {
            throw new BizException(BizCode.ARTICLE_NOT_FOUND, BizCode.ARTICLE_NOT_FOUND.getMessage(), 404);
        }
        if (existing.getStatus() == STATUS_OFFLINE) {
            return "文章已是下线状态";
        }
        assertTransition(existing.getStatus(), STATUS_OFFLINE);
        Article patch = new Article();
        patch.setId(id);
        patch.setVersion(existing.getVersion());
        patch.setStatus(STATUS_OFFLINE);
        patch.setUpdatedAt(LocalDateTime.now());
        if (articleMapper.updateById(patch) == 0) {
            throw new BizException(BizCode.VERSION_CONFLICT);
        }
        // 与作者自服下线同口径结算分类计数（发布态→非发布态：-1）
        if (existing.getStatus() == STATUS_PUBLISHED) {
            adjustCategoryCount(existing.getCategoryId(), -1, STATUS_PUBLISHED);
        }
        return "已下线";
    }

    /**
     * 管理员置顶/取消置顶（UC-A-02"置顶任意文章"；PATCH /api/articles/{id}/top）。
     * top_flag 是治理属性不是编辑内容：单独窄接口，避免作者用整包 PUT 也能改置顶
     * （applyEditableFields 里根本没有 topFlag 的 set ——白名单式字段授权）。
     */
    @Transactional
    public void setTop(Long id, Integer topFlag) {
        Article existing = articleMapper.selectById(id);
        if (existing == null) {
            throw new BizException(BizCode.ARTICLE_NOT_FOUND, BizCode.ARTICLE_NOT_FOUND.getMessage(), 404);
        }
        articleMapper.update(null, new LambdaUpdateWrapper<Article>()
                .eq(Article::getId, id)
                .set(Article::getTopFlag, topFlag)
                .set(Article::getUpdatedAt, LocalDateTime.now()));
    }

    // ==================================================================
    // 面向 M7 AI 批量补全的窄接口（治理动作的"写库手"，权限判断在调用方完成）
    // ==================================================================

    /**
     * 文章是否已有标签（batch 幂等跳过判定的读侧）。
     * 只查关联表存在性不拉名字，selectCount 语义最直接。
     */
    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public boolean hasTags(Long articleId) {
        return articleTagMapper.selectCount(new LambdaQueryWrapper<ArticleTag>()
                .eq(ArticleTag::getArticleId, articleId)) > 0;
    }

    /**
     * 写入 AI 摘要（batch 专用）。窄列 UPDATE 而非整包 updateById：
     * ① 绕开乐观锁冲突面——管理员补摘要不该踢掉作者正在编辑的页面（作者端的
     *    version 检查会因我们的写入 +1 而误报 2004；这里用 setSql 原子递增 version
     *    让仍在编辑的窗口下次保存时收到"真实冲突"提示，语义诚实）；
     * ② summary_source=1 与文本同一语句写入，杜绝"文本落了、来源位没落"的半态。
     * <p>where 里带 summary IS NULL OR ''：并发双跑同一批时后到者更新 0 行——
     * "只补空缺、不覆盖已有"的幂等由数据库执法，比应用层 check-then-write 可靠。
     */
    @org.springframework.transaction.annotation.Transactional
    public void applyAiSummary(Long articleId, String summary) {
        articleMapper.update(null, new LambdaUpdateWrapper<Article>()
                .eq(Article::getId, articleId)
                .and(w -> w.isNull(Article::getSummary).or().eq(Article::getSummary, ""))
                .set(Article::getSummary, summary)
                .set(Article::getSummarySource, 1)
                .setSql("version = version + 1")
                .set(Article::getUpdatedAt, LocalDateTime.now()));
    }

    /**
     * 写入 AI 标签（batch 专用）：直接复用 rebuildTags——article_count 冗余结算、
     * 大小写去重、名称合规都在原实现里（口径单一事实源；AI 侧另起炉灶必漂移）。
     * 注意 rebuildTags 是"覆盖式重建"，调用方（AiBatchService）已保证仅在
     * "文章原本无标签"时进入，不会误删作者手挂的标签。
     */
    @org.springframework.transaction.annotation.Transactional
    public void applyAiTags(Long articleId, List<String> tags) {
        rebuildTags(articleId, tags);
    }

    // ==================================================================
    // 内部：校验 / 装配
    // ==================================================================

    /** 查询存在的文章并校验属主（管理员放行编辑入口用 me.isAdmin；删除等敏感操作按调用点决定） */
    private Article requireOwned(LoginUser me, Long id, String ownerFailMsg) {
        Article article = articleMapper.selectById(id);
        if (article == null) {
            throw new BizException(BizCode.ARTICLE_NOT_FOUND, BizCode.ARTICLE_NOT_FOUND.getMessage(), 404);
        }
        if (!article.getUserId().equals(me.getUserId()) && !isAdmin(me)) {
            // 2002+403：错误码表"无权操作他人文章"归 403 语义
            throw new BizException(BizCode.ARTICLE_NOT_OWNER, ownerFailMsg, 403);
        }
        return article;
    }

    /**
     * 详情可见性矩阵（本模块安全语义的核心，越权测试用例对着它写）：
     * 已发布 → 所有人（含匿名）；草稿/下线 → 仅作者本人与 ADMIN；其余一律 2001。
     *
     * <p>为什么草稿被他人访问回 2001 而不是 2002："它存在但你看不到"本身就是泄露
     * （枚举攻击者可测出标题 ID 的占用情况）。不可见即不存在，与登录防枚举同理。
     */
    private void assertReadable(Article article, LoginUser me) {
        if (article.getStatus() == STATUS_PUBLISHED) {
            return;
        }
        boolean owner = me != null && article.getUserId().equals(me.getUserId());
        boolean admin = me != null && isAdmin(me);
        if (!owner && !admin) {
            throw new BizException(BizCode.ARTICLE_NOT_FOUND, BizCode.ARTICLE_NOT_FOUND.getMessage(), 404);
        }
    }

    private boolean isAdmin(LoginUser me) {
        return me != null && me.getRoles() != null && me.getRoles().contains("ROLE_ADMIN");
    }

    /** 状态机矩阵判定：非法流转统一 1005（文档 §4"非法流转统一 1005"） */
    private void assertTransition(Integer from, Integer to) {
        Set<Integer> allowed = TRANSITIONS.getOrDefault(from, Set.of());
        if (!allowed.contains(to)) {
            throw new BizException(BizCode.FORBIDDEN, "非法的状态流转: " + from + "→" + to, 403);
        }
    }

    /** 分类存在性校验：非空即必须存在 → 2003（错误码表） */
    private void validateCategory(Long categoryId) {
        if (categoryId != null && categoryMapper.selectById(categoryId) == null) {
            throw new BizException(BizCode.CATEGORY_NOT_FOUND);
        }
    }

    /**
     * DTO → 实体：只搬运"作者可编辑"的白名单字段。
     * 正文相关三件套（contentMd / contentText / wordCount）在这里一次生成——
     * content_text 与 word_count 永远派生自 content_md，给 DTO 开口子传它们
     * 只会制造"正文与纯文本不一致"的脏数据机会。
     */
    private void applyEditableFields(Article entity, ArticleSaveDTO dto) {
        entity.setTitle(dto.getTitle().trim());
        entity.setCategoryId(dto.getCategoryId());
        entity.setCover(dto.getCover());
        entity.setContentMd(dto.getContentMd());
        String plain = MarkdownProcessor.extractText(dto.getContentMd());
        entity.setContentText(plain);
        entity.setWordCount(MarkdownProcessor.countWords(plain));
        if (dto.getSummary() != null && !dto.getSummary().isBlank()) {
            entity.setSummary(dto.getSummary().trim());
            entity.setSummarySource(dto.getSummarySource() == null ? 0 : dto.getSummarySource());
        } else {
            // 用户没填摘要：留空即可，AI 摘要在 M6 作为可选增强，不阻塞保存（AI 建议性原则，系统设计 §2）
            entity.setSummary(null);
            entity.setSummarySource(0);
        }
    }

    /**
     * 重建文章-标签关联（§3.9"先 DELETE 后批量 INSERT，同一事务"）：
     * 按名取/建标签 → 覆盖式重建。article_count 冗余列同步 +/-（先清旧再建新，
     * 计数净变化正确，不需要 diff 算法）。
     */
    private void rebuildTags(Long articleId, List<String> tagNames) {
        // 旧关联与计数回退
        List<Long> oldTagIds = articleTagMapper.selectList(new LambdaQueryWrapper<ArticleTag>()
                        .eq(ArticleTag::getArticleId, articleId))
                .stream().map(ArticleTag::getTagId).toList();
        if (!oldTagIds.isEmpty()) {
            articleTagMapper.delete(new LambdaQueryWrapper<ArticleTag>()
                    .eq(ArticleTag::getArticleId, articleId));
            for (Long t : oldTagIds) {
                bumpTagCount(t, -1);
            }
        }
        if (tagNames == null || tagNames.isEmpty()) {
            return;
        }
        // LinkedHashSet：去重且保持用户录入顺序（编辑器回显次序稳定）
        Set<String> names = new LinkedHashSet<>();
        for (String raw : tagNames) {
            if (raw == null) continue;
            String name = raw.trim();
            if (!name.isEmpty() && name.length() <= 30) {
                names.add(name);
            }
        }
        for (String name : names) {
            Tag tag = findOrCreateTag(name);
            ArticleTag link = new ArticleTag();
            link.setArticleId(articleId);
            link.setTagId(tag.getId());
            articleTagMapper.insert(link);
            bumpTagCount(tag.getId(), +1);
        }
    }

    /**
     * 按名取标签，无则建（UC-U-04"输入标签"：新词自动补建是文档承诺的行为）。
     *
     * <p>并发重名兜底：两请求同时给 "Go" 建标签时，后插者撞 uk_name 抛异常，
     * 这里 catch 后回查一次拿胜者行。比"先锁再查"轻，博客写并发量级下撞键概率极低，
     * 撞了也只多一次查询——正确性与简单性都要。
     */
    private Tag findOrCreateTag(String name) {
        Tag exist = tagMapper.selectOne(new LambdaQueryWrapper<Tag>().eq(Tag::getName, name));
        if (exist != null) {
            return exist;
        }
        Tag fresh = new Tag();
        fresh.setName(name);
        fresh.setColor("#409EFF");
        fresh.setArticleCount(0);
        fresh.setCreatedAt(LocalDateTime.now());
        fresh.setUpdatedAt(LocalDateTime.now());
        try {
            tagMapper.insert(fresh);
            return fresh;
        } catch (org.springframework.dao.DuplicateKeyException e) {
            return tagMapper.selectOne(new LambdaQueryWrapper<Tag>().eq(Tag::getName, name));
        }
    }

    /** 标签冗余计数增减：直接 UPDATE 表达式列，不用"读-改-写"（两文章同时保存会互相吞计数） */
    private void bumpTagCount(Long tagId, int delta) {
        tagMapper.update(null, new LambdaUpdateWrapper<Tag>()
                .eq(Tag::getId, tagId)
                .setSql("article_count = GREATEST(article_count + (" + delta + "), 0)"));
    }

    /** 分类冗余计数增减（仅对"已发布可见"文章计，分类页只展示发布态） */
    private void adjustCategoryCount(Long categoryId, int delta, Integer status) {
        if (categoryId == null || status == null || status != STATUS_PUBLISHED) {
            return;
        }
        categoryMapper.update(null, new LambdaUpdateWrapper<Category>()
                .eq(Category::getId, categoryId)
                .setSql("article_count = GREATEST(article_count + (" + delta + "), 0)"));
    }

    /** 列表页专用：排除 MEDIUMTEXT 大字段的列裁剪（select 清单 = DDL 列去掉 content_md/content_text） */
    private LambdaQueryWrapper<Article> selectWithoutBody() {
        return new LambdaQueryWrapper<Article>()
                .select(Article::getId, Article::getUserId, Article::getCategoryId,
                        Article::getTitle, Article::getSummary, Article::getSummarySource,
                        Article::getCover, Article::getWordCount, Article::getStatus,
                        Article::getTopFlag, Article::getAllowComment, Article::getViewCount,
                        Article::getLikeCount, Article::getCommentCount, Article::getPublishedAt,
                        Article::getCreatedAt, Article::getUpdatedAt, Article::getVersion);
    }

    /** sort 白名单映射：只认 published_at/created_at/view_count 三键，其余落默认（防注入 & 契约稳定） */
    private void applySort(LambdaQueryWrapper<Article> qw, String sort) {
        if (sort == null || sort.isBlank()) {
            qw.orderByDesc(Article::getPublishedAt);
            return;
        }
        String[] parts = sort.split(",");
        String col = parts[0].trim();
        boolean asc = parts.length > 1 && "asc".equalsIgnoreCase(parts[1].trim());
        switch (col) {
            case "created_at" -> qw.orderBy(true, asc, Article::getCreatedAt);
            case "view_count" -> qw.orderBy(true, asc, Article::getViewCount);
            default -> qw.orderBy(true, asc, Article::getPublishedAt);
        }
    }

    // ==================================================================
    // 装配（实体 → VO）
    // ==================================================================

    /** 详情装配：主表 + 作者/分类/标签三聚合。 */
    private ArticleVO assembleDetail(Article a) {
        ArticleVO.ArticleVOBuilder b = ArticleVO.builder()
                .id(a.getId()).userId(a.getUserId()).title(a.getTitle())
                .cover(a.getCover()).summary(a.getSummary()).summarySource(a.getSummarySource())
                .contentMd(a.getContentMd())
                .viewCount(a.getViewCount()).likeCount(a.getLikeCount()).commentCount(a.getCommentCount())
                .status(a.getStatus()).allowComment(a.getAllowComment())
                .publishedAt(a.getPublishedAt())
                .createdAt(a.getCreatedAt()).updatedAt(a.getUpdatedAt())
                .version(a.getVersion());
        if (a.getCategoryId() != null) {
            Category c = categoryMapper.selectById(a.getCategoryId());
            if (c != null) {
                b.category(ArticleVO.CategoryBrief.builder().id(c.getId()).name(c.getName()).build());
            }
        }
        SysUser author = userMapper.selectById(a.getUserId());
        if (author != null) {
            b.author(ArticleVO.AuthorBrief.builder()
                    .id(author.getId()).nickname(author.getNickname()).avatar(author.getAvatar()).build());
        }
        b.tags(loadTagBriefs(List.of(a.getId())).getOrDefault(a.getId(), List.of()));
        return b.build();
    }

    /**
     * 列表批量装配：作者/分类/标签三张维表各查一次（IN 批查），内存 join。
     *
     * <p>为什么不逐行装配：一页 20 行 × 3 维表 = 60 次查询的 N+1；批查固定 4 条 SQL
     * （主表1 + 三维表各1），这是列表接口最廉价也最有效的防性能债手法，
     * 比引入 MP-Join/联表 XML 的工程复杂度在毕设体量下更划算。
     */
    private List<ArticleVO> toBriefVOList(List<Article> rows) {
        if (rows.isEmpty()) {
            return List.of();
        }
        List<Long> ids = rows.stream().map(Article::getId).toList();
        Map<Long, List<ArticleVO.TagBrief>> tagMap = loadTagBriefs(ids);

        Set<Long> userIds = rows.stream().map(Article::getUserId).collect(Collectors.toSet());
        Map<Long, SysUser> userMap = userMapper.selectBatchIds(userIds).stream()
                .collect(Collectors.toMap(SysUser::getId, u -> u));
        Set<Long> catIds = rows.stream().map(Article::getCategoryId)
                .filter(java.util.Objects::nonNull).collect(Collectors.toSet());
        Map<Long, Category> catMap = catIds.isEmpty() ? Collections.emptyMap()
                : categoryMapper.selectBatchIds(catIds).stream()
                        .collect(Collectors.toMap(Category::getId, c -> c));

        List<ArticleVO> out = new ArrayList<>(rows.size());
        for (Article a : rows) {
            SysUser u = userMap.get(a.getUserId());
            Category c = a.getCategoryId() == null ? null : catMap.get(a.getCategoryId());
            out.add(ArticleVO.builder()
                    .id(a.getId()).userId(a.getUserId()).title(a.getTitle())
                    .cover(a.getCover()).summary(a.getSummary()).summarySource(a.getSummarySource())
                    .viewCount(a.getViewCount()).likeCount(a.getLikeCount()).commentCount(a.getCommentCount())
                    .status(a.getStatus()).allowComment(a.getAllowComment())
                    .publishedAt(a.getPublishedAt()).createdAt(a.getCreatedAt()).updatedAt(a.getUpdatedAt())
                    .version(a.getVersion())
                    .author(u == null ? null : ArticleVO.AuthorBrief.builder()
                            .id(u.getId()).nickname(u.getNickname()).avatar(u.getAvatar()).build())
                    .category(c == null ? null : ArticleVO.CategoryBrief.builder()
                            .id(c.getId()).name(c.getName()).build())
                    .tags(tagMap.getOrDefault(a.getId(), List.of()))
                    .build());
        }
        return out;
    }

    /** 一批文章的标签聚合：一次 article_tag JOIN tag 的 IN 查询，按文章分组 */
    private Map<Long, List<ArticleVO.TagBrief>> loadTagBriefs(List<Long> articleIds) {
        List<ArticleTag> links = articleTagMapper.selectList(new LambdaQueryWrapper<ArticleTag>()
                .in(ArticleTag::getArticleId, articleIds));
        if (links.isEmpty()) {
            return Collections.emptyMap();
        }
        Set<Long> tagIds = links.stream().map(ArticleTag::getTagId).collect(Collectors.toSet());
        Map<Long, Tag> tags = tagMapper.selectBatchIds(tagIds).stream()
                .collect(Collectors.toMap(Tag::getId, t -> t));
        Map<Long, List<ArticleVO.TagBrief>> grouped = new HashMap<>();
        for (ArticleTag link : links) {
            Tag t = tags.get(link.getTagId());
            if (t != null) {
                grouped.computeIfAbsent(link.getArticleId(), k -> new ArrayList<>())
                        .add(ArticleVO.TagBrief.builder().id(t.getId()).name(t.getName()).build());
            }
        }
        return grouped;
    }

    /**
     * 列表查询参数对象（Service 层防腐：Controller 把散落的 @RequestParam 归拢成结构，
     * 这里再解析排序/权限口径——sort 拆出的两个布尔/枚举值不适合在签名上满天飞）。
     */
    public static class ArticleQuery {
        public long pageNum = 1;
        public long pageSize = 10;
        public Long categoryId;
        public Long tagId;
        public String keyword;
        public Integer status;
        public Long authorId;
        public boolean mine;
        public boolean adminScope;
        public Long currentUserId;
        public String sort;
    }
}
