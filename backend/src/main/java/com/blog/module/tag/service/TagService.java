package com.blog.module.tag.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.blog.common.BizCode;
import com.blog.common.BizException;
import com.blog.module.article.entity.ArticleTag;
import com.blog.module.article.mapper.ArticleTagMapper;
import com.blog.module.tag.dto.TagSaveDTO;
import com.blog.module.tag.entity.Tag;
import com.blog.module.tag.mapper.TagMapper;
import com.blog.module.tag.vo.TagVO;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 标签服务（M4）—— UC-A-04：标签增删改查 + 合并；GET /api/tags?cloud=true 热度权重。
 *
 * <p>与分类（纯配置数据）最大的差异是标签是"半自由文本"：M3 的发文链路会
 * findOrCreateTag 自动补建标签，所以标签表会持续长出无人治理的孤儿/近义标签
 * （"Java" 与 "java 编程"），合并接口（POST /api/tags/merge）就是给管理员的
 * 治理闭环——这也是它比分类多出来的核心逻辑。
 */
@Service
@RequiredArgsConstructor
public class TagService {

    private final TagMapper tagMapper;
    private final ArticleTagMapper articleTagMapper;
    /**
     * M9：标签名/存续状态进文章详情与列表 VO（TagBrief），改标签=改文章的公开视图。
     * 受影响文章理论可枚举（article_tag 反查），但列表键无法精准定位（md5 不可逆），
     * 治理动作低频，一律 evictAll 全量失效——见 ArticleCacheService#evictAll 的取舍论证。
     */
    private final com.blog.module.article.service.ArticleCacheService articleCache;

    /** 默认主题色：与 Element Plus 主色一致（DDL 列默认值同款，两处同值便于对照） */
    private static final String DEFAULT_COLOR = "#409EFF";

    /**
     * GET /api/tags —— 列表 / 标签云。
     *
     * <p>排序策略二态：cloud=true 按热度降序（标签云"越常用越显眼"的阅读预期），
     * 普通列表按 id 升序（治理页要稳定的顺序方便翻页对账）。权重只在 cloud 下计算，
     * 普通调用省一趟 max 扫描的语义噪音。
     */
    public List<TagVO> list(boolean cloud) {
        List<Tag> tags = tagMapper.selectList(new LambdaQueryWrapper<Tag>()
                .orderByDesc(cloud, Tag::getArticleCount)      // 条件式 order：boolean 开关控制子句是否拼接
                .orderByAsc(Tag::getId));
        int max = cloud ? tags.stream().mapToInt(t -> t.getArticleCount() == null ? 0 : t.getArticleCount()).max().orElse(0) : 0;
        return tags.stream().map(t -> toVO(t, cloud, max)).toList();
    }

    /** POST /api/tags —— 新增（ADMIN，tag:manage）；重名先查重后插入，唯一键兜并发 */
    @Transactional
    public TagVO create(TagSaveDTO dto) {
        String name = dto.getName().trim();
        assertNameFree(name, null);
        Tag tag = new Tag();
        tag.setName(name);
        tag.setColor(dto.getColor() == null ? DEFAULT_COLOR : dto.getColor());
        tag.setArticleCount(0);
        LocalDateTime now = LocalDateTime.now();
        tag.setCreatedAt(now);
        tag.setUpdatedAt(now);
        try {
            tagMapper.insert(tag);
        } catch (DuplicateKeyException e) {
            throw new BizException(BizCode.TAG_NAME_DUPLICATE);
        }
        return toVO(tag, false, 0);
    }

    /**
     * PUT /api/tags/{id} —— 修改（ADMIN）。
     *
     * <p>改名的并发风险：uk_name 只保证唯一，不保证"改名后与 M3 发文链路的
     * findOrCreateTag 撞车"——那属于正常业务竞态（同名即复用），无需特殊处理；
     * 本接口只需要处理"管理员手动改成一个已存在的名字"这种明确错误。
     */
    @Transactional
    public TagVO update(Long id, TagSaveDTO dto) {
        require(id);
        String name = dto.getName().trim();
        assertNameFree(name, id);
        LambdaUpdateWrapper<Tag> uw = new LambdaUpdateWrapper<Tag>()
                .eq(Tag::getId, id)
                .set(Tag::getName, name)
                .set(Tag::getUpdatedAt, LocalDateTime.now());
        if (dto.getColor() != null) {
            uw.set(Tag::getColor, dto.getColor());
        }
        tagMapper.update(null, uw);
        articleCache.evictAll(); // M9：改名/改色波及所有挂载文章的公开视图，全量失效
        return toVO(require(id), false, 0);
    }

    /**
     * DELETE /api/tags/{id} —— 删除并解除文章关联（《API接口设计.md》§5 权限列：ADMIN）。
     *
     * <p>关联表 article_tag 是物理删除（表无 deleted 列，§3.9）：标签行留逻辑删除痕迹
     * 供审计，关联不留——留着会让文章详情 JOIN 出指向已删标签的记录。
     * 事务内先删关联再删标签，反序则中间态会产生"关联指向已删标签"的脏读窗口。
     */
    @Transactional
    public void delete(Long id) {
        require(id);
        articleTagMapper.delete(new LambdaQueryWrapper<com.blog.module.article.entity.ArticleTag>()
                .eq(com.blog.module.article.entity.ArticleTag::getTagId, id));
        tagMapper.deleteById(id);
        articleCache.evictAll(); // M9：解关联波及所有挂载文章的 tags 字段，全量失效
    }

    /**
     * POST /api/tags/merge —— 合并标签（ADMIN）：from 的全部文章关联改挂 to，from 逻辑删除。
     *
     * <p>五步流水（全程单事务）：
     * 1) 双方存在性校验（含 from==to 自合并拒绝——语义是 no-op，但删 from 会误删 to 的关联，必须显式拒）；
     * 2) 集合差搬移：把 from 的关联中"to 尚未持有"的那批逐行 insert 到 to——重叠文章
     *    （同一篇同时挂 from 和 to）天然跳过，不会撞 uk_article_tag。为什么不用
     *    INSERT IGNORE ... SELECT 一条 SQL 搬完：M0 装配的 BlockAttackInnerInterceptor
     *    解析不了 INSERT-SELECT 形态（beforePrepare 抛 UnsupportedOperationException，
     *    M4 实测踩坑），且逐行方案在两库（MySQL/H2）语义完全一致，博客量级下代价可忽略；
     * 3) DELETE from 旧关联；
     * 4) 重算双方冗余计数：合并影响任意多文章的发布态组合，逐个 +/- 的误差窗口太大，
     *    按事实表 COUNT 重算让冗余列自我修复（to 还会吸收 from 的重叠引用，+/- 根本算不清）；
     * 5) 逻辑删除 from 行。
     *
     * <p>为什么"目标不存在时拒绝"而不是"自动改名合并"：合并是治理动作，静默新建
     * 目标标签会把管理员的笔误放大成新数据，宁可 2202 让他显式确认。
     */
    @Transactional
    public TagVO merge(TagSaveDTO.MergeRequest req) {
        if (req.getFromTagId().equals(req.getToTagId())) {
            throw new BizException(BizCode.PARAM_ERROR, "不能与自身合并");
        }
        Tag from = require(req.getFromTagId());
        require(req.getToTagId());   // 目标必须已存在（治理动作语义，见方法注释）

        // from 的全部载体文章
        List<Long> fromArticles = articleTagMapper.selectList(new LambdaQueryWrapper<ArticleTag>()
                        .eq(ArticleTag::getTagId, from.getId()))
                .stream().map(ArticleTag::getArticleId).toList();
        // to 已有的载体（差集外者重叠，跳过插入即"静默去重"，等价 INSERT IGNORE）
        Set<Long> toArticles = new HashSet<>(articleTagMapper.selectList(new LambdaQueryWrapper<ArticleTag>()
                        .eq(ArticleTag::getTagId, req.getToTagId()))
                .stream().map(ArticleTag::getArticleId).toList());
        LocalDateTime linkTime = LocalDateTime.now();
        for (Long articleId : fromArticles) {
            if (toArticles.add(articleId)) {   // add 返回 true = to 原先没有这篇的关联
                ArticleTag link = new ArticleTag();
                link.setArticleId(articleId);
                link.setTagId(req.getToTagId());
                link.setCreatedAt(linkTime);
                articleTagMapper.insert(link);
            }
        }
        articleTagMapper.delete(new LambdaQueryWrapper<ArticleTag>().eq(ArticleTag::getTagId, from.getId()));
        recountTag(from.getId());           // from 归零（先算后删行，让计数变更留痕在已删行上也无害）
        recountTag(req.getToTagId());
        tagMapper.deleteById(from.getId());
        articleCache.evictAll(); // M9：合并波及 from/to 全部载体文章的 tags 字段，全量失效
        return toVO(require(req.getToTagId()), false, 0);
    }

    // ==================== 内部工具 ====================

    /** 按 id 取未删除标签，无则 2202（404 语义与分类 2003 对齐） */
    public Tag require(Long id) {
        Tag tag = id == null ? null : tagMapper.selectById(id);
        if (tag == null) {
            throw new BizException(BizCode.TAG_NOT_FOUND, BizCode.TAG_NOT_FOUND.getMessage(), 404);
        }
        return tag;
    }

    /** 重名查重：excludeId 支持"改名不成真改名"放行（同名同 id） */
    private void assertNameFree(String name, Long excludeId) {
        if (name.isEmpty()) {
            throw new BizException(BizCode.PARAM_ERROR, "标签名不能为空");
        }
        Tag dup = tagMapper.selectOne(new LambdaQueryWrapper<Tag>()
                .eq(Tag::getName, name).last("LIMIT 1"));
        if (dup != null && !dup.getId().equals(excludeId)) {
            throw new BizException(BizCode.TAG_NAME_DUPLICATE);
        }
    }

    /** 冗余计数重算：把 tag.article_count 校正为事实表（未删除文章）的引用数（口径见 Mapper 注释） */
    private void recountTag(Long tagId) {
        long real = tagMapper.countLiveRef(tagId);
        tagMapper.update(null, new LambdaUpdateWrapper<Tag>()
                .eq(Tag::getId, tagId)
                .set(Tag::getArticleCount, (int) real));
    }

    /** 实体 → VO；cloud 模式下线性归一 articleCount 到 [1,5] 权重（全零回中位 3，见 VO 注释） */
    private TagVO toVO(Tag t, boolean cloud, int max) {
        TagVO vo = new TagVO();
        vo.setId(t.getId());
        vo.setName(t.getName());
        vo.setColor(t.getColor());
        vo.setArticleCount(t.getArticleCount());
        if (cloud) {
            int count = t.getArticleCount() == null ? 0 : t.getArticleCount();
            vo.setWeight(max <= 0 ? 3.0 : 1.0 + 4.0 * count / max);
        }
        return vo;
    }
}
