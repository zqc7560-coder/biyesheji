package com.blog.module.tag.controller;

import com.blog.common.Result;
import com.blog.module.tag.dto.TagSaveDTO;
import com.blog.module.tag.service.TagService;
import com.blog.module.tag.vo.TagVO;
import io.swagger.v3.oas.annotations.Operation;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 标签接口（M4）—— 路径逐条对齐《API接口设计.md》§5：
 * GET /api/tags?cloud=true（公开）+ POST/PUT/DELETE/merge（ADMIN，tag:manage）。
 *
 * <p>合并口 POST /api/tags/merge 特意声明在 /{id} 之前不存在路由歧义问题：
 * Spring MVC 的字面量路径优先级高于路径变量（"merge" 不会被当成 PUT /{id} 的 id），
 * 且合并是 POST、改标签是 PUT——方法+模式双重区分，无需排序技巧。
 */
@io.swagger.v3.oas.annotations.tags.Tag(name = "标签", description = "标签列表/标签云/新增/修改/删除/合并（UC-A-04）")  // swagger @Tag 与实体 Tag 重名，FQN 消歧（M3 ArticleController 同款问题的收口）
@RestController
@RequestMapping("/api/tags")
@RequiredArgsConstructor
public class TagController {

    private final TagService tagService;

    /** GET /api/tags —— 列表；cloud=true 返回热度权重（标签云视图，UC-V-06） */
    @Operation(summary = "标签列表/标签云")
    @GetMapping
    public Result<List<TagVO>> list(@RequestParam(defaultValue = "false") boolean cloud) {
        return Result.ok(tagService.list(cloud));
    }

    /** POST /api/tags —— 新增 {name,color}（ADMIN） */
    @Operation(summary = "新增标签")
    @PostMapping
    @PreAuthorize("hasAuthority('tag:manage')")
    public Result<TagVO> create(@Valid @RequestBody TagSaveDTO dto) {
        return Result.ok(tagService.create(dto));
    }

    /** POST /api/tags/merge —— 合并 {fromTagId,toTagId}（ADMIN；from 关联改挂 to，from 逻辑删除） */
    @Operation(summary = "合并标签")
    @PostMapping("/merge")
    @PreAuthorize("hasAuthority('tag:manage')")
    public Result<TagVO> merge(@Valid @RequestBody TagSaveDTO.MergeRequest req) {
        return Result.ok(tagService.merge(req));
    }

    /** PUT /api/tags/{id} —— 修改（ADMIN） */
    @Operation(summary = "修改标签")
    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('tag:manage')")
    public Result<TagVO> update(@PathVariable Long id, @Valid @RequestBody TagSaveDTO dto) {
        return Result.ok(tagService.update(id, dto));
    }

    /** DELETE /api/tags/{id} —— 删除并解除文章关联（ADMIN，§5 权限列：ADMIN） */
    @Operation(summary = "删除标签")
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('tag:manage')")
    public Result<Void> delete(@PathVariable Long id) {
        tagService.delete(id);
        return Result.ok(null);
    }
}
