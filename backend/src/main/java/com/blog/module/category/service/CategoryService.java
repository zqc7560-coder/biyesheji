package com.blog.module.category.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.blog.common.BizCode;
import com.blog.common.BizException;
import com.blog.module.category.dto.CategorySaveDTO;
import com.blog.module.category.entity.Category;
import com.blog.module.category.mapper.CategoryMapper;
import com.blog.module.category.vo.CategoryTreeVO;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 分类服务（M4）—— UC-A-03：分类增删改查与排序；分类下仍有文章时禁止删除。
 *
 * <p>本层承担三条设计约定的收口：
 * 1) 两级树形约束（《数据库设计.md》§3.7 "本期只用两级"）——挂在 Service 而不是 CHECK
 *    约束或前端：约束表达不了"父的父必须是 0"这种跨行语义，前端约束可被 curl 绕过；
 * 2) 删除守卫——数 article 事实（countLiveArticles）而非冗余列 article_count，
 *    草稿/下线也拦，防止放行后产生指向已删分类的孤儿文章；
 * 3) 重名——Service 先查重给友好文案（2103），uk_name 唯一键兜并发窗口（catch 后转同码），
 *    与 M2 注册、M3 建标签的"查重+唯一键双保险"同一套路。
 */
@Service
@RequiredArgsConstructor
public class CategoryService {

    private final CategoryMapper categoryMapper;
    /**
     * M9：分类名进文章详情/列表 VO（CategoryBrief），改名波及全部挂载文章——
     * 与 TagService 同一论证：治理动作低频、列表键不可精准定位，evictAll 全量失效。
     * （删除口不用挂失效：2101 守卫保证"有文章的分类删不掉"，公开视图不受影响。）
     */
    private final com.blog.module.article.service.ArticleCacheService articleCache;

    /**
     * GET /api/categories —— 分类树（公开，UC-V-06 导航数据源）。
     *
     * <p>实现口径：全量拉未删除分类（量级 = 管理配置数据，几十行封顶，无需分页/缓存），
     * 内存两趟装配成树；父级已被删除的"孤儿二级"提升为顶级输出而不是丢弃——
     * 宁可见不可丢：丢了这个分类及其计数，前端筛选就再也进不去了。
     */
    public List<CategoryTreeVO> tree() {
        List<Category> all = categoryMapper.selectList(new LambdaQueryWrapper<Category>()
                .orderByAsc(Category::getSortOrder).orderByAsc(Category::getId));
        Map<Long, CategoryTreeVO> nodeById = all.stream()
                .collect(Collectors.toMap(Category::getId, this::toNode, (a, b) -> a, java.util.LinkedHashMap::new));
        List<CategoryTreeVO> roots = new ArrayList<>();
        for (CategoryTreeVO node : nodeById.values()) {
            CategoryTreeVO parent = node.getParentId() == null || node.getParentId() == 0L
                    ? null : nodeById.get(node.getParentId());
            if (parent != null) {
                parent.getChildren().add(node);
            } else {
                roots.add(node);
            }
        }
        // 二级按 sortOrder 已在查询里全局有序，children 的相对顺序天然保持，无需再排
        return roots;
    }

    /**
     * POST /api/categories —— 新增（ADMIN，category:manage）。
     *
     * <p>slug 缺省策略：留空时填 "cat-{id}" 占位（列 NOT NULL 之外的 UNIQUE 不涉及，
     * 见 DDL slug 无唯一键）。本期路由按 id 过滤，slug 只是 SEO 预留位，
     * 占位值保证未来启用 slug 路由时数据非空、可平滑回填。
     */
    @Transactional
    public CategoryTreeVO create(CategorySaveDTO dto) {
        String name = dto.getName().trim();
        assertNameFree(name, null);
        assertParentOk(null, dto.getParentId());

        Category cat = new Category();
        cat.setName(name);
        cat.setSlug(blankToNull(dto.getSlug()));
        cat.setParentId(dto.getParentId() == null ? 0L : dto.getParentId());
        cat.setSortOrder(dto.getSortOrder() == null ? 0 : dto.getSortOrder());
        cat.setDescription(blankToNull(dto.getDescription()));
        cat.setArticleCount(0);   // 冗余计数从 0 起步，之后全部由 M3 的发布/迁移链路 +/-
        LocalDateTime now = LocalDateTime.now();
        cat.setCreatedAt(now);
        cat.setUpdatedAt(now);
        try {
            categoryMapper.insert(cat);
        } catch (DuplicateKeyException e) {
            // 并发窗口：查完重名到插入之间被抢先——唯一键兜住，转成与查重同款业务码
            throw new BizException(BizCode.CATEGORY_NAME_DUPLICATE);
        }
        // MP 的 insert 回写自增主键后才能定 slug，故补一次轻量 UPDATE（id 已知，无并发风险）
        if (cat.getSlug() == null) {
            cat.setSlug("cat-" + cat.getId());
            categoryMapper.update(null, new LambdaUpdateWrapper<Category>()
                    .eq(Category::getId, cat.getId()).set(Category::getSlug, cat.getSlug()));
        }
        return toNode(cat);   // 防腐层约定：回 VO 不回实体（deleted/时间戳等治理列不出接口）
    }

    /**
     * PUT /api/categories/{id} —— 修改（ADMIN）。null 字段=不变更（MP updateById 语义），
     * 前端表单可只回传改动项。
     *
     * <p>parentId 变更校验三查（都失败即拒绝变更，整单原子）：
     * a) 新父必须存在；b) 新父必须是顶级（两级封顶）；c) 不允许把自己设为自己的父
     *    （自环会让 tree() 装配时节点永远挂不进 roots，接口静默丢数据——必须在入口拦）。
     */
    @Transactional
    public CategoryTreeVO update(Long id, CategorySaveDTO dto) {
        Category exist = require(id);
        String name = dto.getName().trim();
        assertNameFree(name, id);
        if (dto.getParentId() != null && !dto.getParentId().equals(exist.getParentId())) {
            assertParentOk(id, dto.getParentId());
            // 带子级的分类不允许降级为二级：那会把树撑成三级，违背 §3.7 两级约定
            boolean hasChildren = categoryMapper.selectCount(new LambdaQueryWrapper<Category>()
                    .eq(Category::getParentId, id)) > 0;
            if (hasChildren && dto.getParentId() != 0L) {
                throw new BizException(BizCode.CATEGORY_HAS_CHILDREN, "该分类下有子分类，不能再挂到其它分类下（两级封顶）");
            }
        }

        Category patch = new Category();
        patch.setId(id);
        patch.setName(name);
        patch.setSlug(blankToNull(dto.getSlug()));
        patch.setParentId(dto.getParentId());
        patch.setSortOrder(dto.getSortOrder());
        patch.setDescription(dto.getDescription());   // 描述清空传 "" 即可：MP null-skip 只认 null
        patch.setUpdatedAt(LocalDateTime.now());
        categoryMapper.updateById(patch);
        articleCache.evictAll(); // M9：改名/换父波及挂载文章的 CategoryBrief，全量失效
        return toNode(require(id));   // 回读返回最新态（updateById 对 null 列不更新，本地对象并不等于库内值）
    }

    /**
     * DELETE /api/categories/{id} —— 删除（ADMIN，逻辑删除）。
     *
     * <p>双守卫顺序刻意"先轻后重"：子分类守卫是单表 COUNT，文章守卫是跨表 COUNT，
     * 先跑便宜的。两者都必须存在：
     * 1) 有未删除文章挂载 → 2101（《需求分析.md》UC-A-03 的硬要求）；
     * 2) 有子分类 → 2102，否则删父留子会造出指向已删父的孤儿二级，树装配口径
     *    （孤儿升顶级）只兜"历史数据"，不该由删除操作主动制造。
     */
    @Transactional
    public void delete(Long id) {
        require(id);
        boolean hasChildren = categoryMapper.selectCount(new LambdaQueryWrapper<Category>()
                .eq(Category::getParentId, id)) > 0;
        if (hasChildren) {
            throw new BizException(BizCode.CATEGORY_HAS_CHILDREN);
        }
        if (categoryMapper.countLiveArticles(id) > 0) {
            throw new BizException(BizCode.CATEGORY_NOT_EMPTY);
        }
        categoryMapper.deleteById(id);   // @TableLogic 全局配置下即 UPDATE deleted=1
    }

    // ==================== 内部工具 ====================

    /** 按 id 取未删除分类，无则 2003（与文章侧 CATEGORY_NOT_FOUND 同码复用） */
    public Category require(Long id) {
        Category cat = id == null ? null : categoryMapper.selectById(id);
        if (cat == null) {
            throw new BizException(BizCode.CATEGORY_NOT_FOUND, BizCode.CATEGORY_NOT_FOUND.getMessage(), 404);
        }
        return cat;
    }

    /**
     * 父级合法性：0/缺省=顶级直通；否则父必须存在且本身是顶级（两级封顶）。
     * selfId 非空（update 场景）时禁自环。
     */
    private void assertParentOk(Long selfId, Long parentId) {
        if (parentId == null || parentId == 0L) {
            return;
        }
        if (selfId != null && selfId.equals(parentId)) {
            throw new BizException(BizCode.PARAM_ERROR, "父分类不能是自己");
        }
        Category parent = categoryMapper.selectById(parentId);
        if (parent == null) {
            throw new BizException(BizCode.CATEGORY_NOT_FOUND, "父分类不存在", 404);
        }
        if (parent.getParentId() != null && parent.getParentId() != 0L) {
            throw new BizException(BizCode.PARAM_ERROR, "分类最多两级：不能在二级分类下再建子分类");
        }
    }

    /** 重名查重：excludeId 传入以支持"改名不成真改名"的更新场景（同名同 id 放行） */
    private void assertNameFree(String name, Long excludeId) {
        if (name.isEmpty()) {
            throw new BizException(BizCode.PARAM_ERROR, "分类名不能为空");
        }
        Category dup = categoryMapper.selectOne(new LambdaQueryWrapper<Category>()
                .eq(Category::getName, name).last("LIMIT 1"));
        if (dup != null && !dup.getId().equals(excludeId)) {
            throw new BizException(BizCode.CATEGORY_NAME_DUPLICATE);
        }
    }

    private CategoryTreeVO toNode(Category c) {
        CategoryTreeVO vo = new CategoryTreeVO();
        vo.setId(c.getId());
        vo.setName(c.getName());
        vo.setSlug(c.getSlug());
        vo.setParentId(c.getParentId());
        vo.setSortOrder(c.getSortOrder());
        vo.setDescription(c.getDescription());
        vo.setArticleCount(c.getArticleCount());
        return vo;
    }

    /** 空串/纯空白归一为 null（可选列统一口径，避免库里存一排 ""） */
    private String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
