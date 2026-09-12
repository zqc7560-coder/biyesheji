package com.blog.module.category.controller;

import com.blog.common.Result;
import com.blog.module.category.dto.CategorySaveDTO;
import com.blog.module.category.service.CategoryService;
import com.blog.module.category.vo.CategoryTreeVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 分类接口（M4）—— 路径逐条对齐《API接口设计.md》§5：
 * GET /api/categories（公开）+ POST/PUT/DELETE（ADMIN，权限点 category:manage）。
 *
 * <p>本层零业务（与 ArticleController 同分层约定）：树装配、两级校验、删除守卫
 * 全在 CategoryService；写口用类级 @PreAuthorize 挂 category:manage——§5 权限列
 * 对写操作统一 ADMIN，逐方法再挂只会制造重复。GET 公开性由 SecurityConfig
 * M4 白名单放行（见该文件注释）。
 */
@Tag(name = "分类", description = "分类树/新增/修改/删除（UC-A-03）")
@RestController
@RequestMapping("/api/categories")
@RequiredArgsConstructor
public class CategoryController {

    private final CategoryService categoryService;

    /** GET /api/categories —— 分类树（含 article_count），游客可访问（SecurityConfig 白名单） */
    @Operation(summary = "分类树")
    @GetMapping
    public Result<List<CategoryTreeVO>> tree() {
        return Result.ok(categoryService.tree());
    }

    /** POST /api/categories —— 新增 {name,parentId,sortOrder}（ADMIN） */
    @Operation(summary = "新增分类")
    @PostMapping
    @PreAuthorize("hasAuthority('category:manage')")
    public Result<CategoryTreeVO> create(@Valid @RequestBody CategorySaveDTO dto) {
        return Result.ok(categoryService.create(dto));
    }

    /** PUT /api/categories/{id} —— 修改（ADMIN；null 字段=不变更） */
    @Operation(summary = "修改分类")
    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('category:manage')")
    public Result<CategoryTreeVO> update(@PathVariable Long id, @Valid @RequestBody CategorySaveDTO dto) {
        return Result.ok(categoryService.update(id, dto));
    }

    /** DELETE /api/categories/{id} —— 删除（ADMIN；有文章 2101 / 有子分类 2102） */
    @Operation(summary = "删除分类")
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('category:manage')")
    public Result<Void> delete(@PathVariable Long id) {
        categoryService.delete(id);
        return Result.ok(null);
    }
}
