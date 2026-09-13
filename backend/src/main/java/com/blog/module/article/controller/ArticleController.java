package com.blog.module.article.controller;

import com.blog.common.PageResult;
import com.blog.common.Result;
import com.blog.module.article.dto.ArticleSaveDTO;
import com.blog.module.article.dto.ArticleStatusDTO;
import com.blog.module.article.service.ArticleService;
import com.blog.module.article.vo.ArticleVO;
import com.blog.security.LoginUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 文章接口（M3）—— 路径逐条对齐《API接口设计.md》§4。
 *
 * <p>本层零业务（《系统设计.md》分层约定）：只做三件事——
 * 1) 参数归拢（散 @RequestParam → ArticleQuery）；
 * 2) 权限粗筛（@PreAuthorize 挂文档"权限"列里明确的权限点；
 *    "作者"这一列的属主约束不在这里做——那是逐资源的行级判断，
 *    属于 Service 层 WHERE user_id 的领地，注解表达不了"这一行是不是你的"）；
 * 3) 包装统一响应信封。
 *
 * <p>登录态获取用 @AuthenticationPrincipal LoginUser：JwtAuthFilter 验签后放进
 * SecurityContext 的 principal，匿名请求时为 null——详情/列表接口据此区分
 * "游客视图 vs 作者视图"，接口本身不强制登录（公开可见性矩阵在 Service 判定）。
 */
@Tag(name = "文章", description = "列表/详情/发布/编辑/状态流转/归档")
@RestController
@RequestMapping("/api/articles")
@RequiredArgsConstructor
public class ArticleController {

    private final ArticleService articleService;

    /**
     * GET /api/articles —— 分页列表。
     *
     * <p>mine=1 的鉴权口径（文档"公开(mine=ADMIN/作者)"）：mine 分支必须登录，
     * 匿名传 mine=1 直接走 401（SecurityConfig 对本路径是 permitAll，
     * 所以在方法内显式拒绝——漏了这步就是把"他人草稿列表"开放成公开接口）。
     */
    @Operation(summary = "文章分页列表")
    @GetMapping
    public Result<PageResult<ArticleVO>> list(@AuthenticationPrincipal LoginUser me,
                                              @RequestParam(defaultValue = "1") long pageNum,
                                              @RequestParam(defaultValue = "10") long pageSize,
                                              @RequestParam(required = false) Long categoryId,
                                              @RequestParam(required = false) Long tagId,
                                              @RequestParam(required = false) String keyword,
                                              @RequestParam(required = false) Integer status,
                                              @RequestParam(required = false) Long authorId,
                                              @RequestParam(required = false, defaultValue = "0") int mine,
                                              @RequestParam(required = false) String sort) {
        ArticleService.ArticleQuery q = new ArticleService.ArticleQuery();
        q.pageNum = Math.max(1, pageNum);
        q.pageSize = Math.min(Math.max(1, pageSize), 100);   // §1.2 上限 100（MP 插件也钳，双保险）
        q.categoryId = categoryId;
        q.tagId = tagId;
        q.keyword = keyword;
        q.status = status;
        q.authorId = authorId;
        q.sort = sort;
        q.currentUserId = me == null ? null : me.getUserId();
        if (mine == 1) {
            if (me == null) {
                // 抛业务异常走 GlobalExceptionHandler：HTTP 401 + 信封 code=1001，
                // 与 SecurityConfig 的匿名拦截出口保持完全一致的状态码语义
                throw new com.blog.common.BizException(com.blog.common.BizCode.UNAUTHORIZED,
                        com.blog.common.BizCode.UNAUTHORIZED.getMessage(), 401);
            }
            q.mine = true;
        } else {
            // 仅 ADMIN 可用 status/authorId 做全站治理视图；普通用户/游客的 status 参数一律忽略
            q.adminScope = me != null && me.getRoles() != null && me.getRoles().contains("ROLE_ADMIN");
        }
        return Result.ok(articleService.pageArticles(q));
    }

    /** GET /api/articles/{id} —— 详情（草稿/下线仅作者与 ADMIN 可见，Service 矩阵判定） */
    @Operation(summary = "文章详情")
    @GetMapping("/{id}")
    public Result<ArticleVO> detail(@AuthenticationPrincipal LoginUser me,
                                    @PathVariable Long id) {
        return Result.ok(articleService.detail(id, me));
    }

    /** POST /api/articles —— 新建（草稿/发布由 status 字段决定）；权限点 article:save */
    @Operation(summary = "新建文章")
    @PostMapping
    @org.springframework.security.access.prepost.PreAuthorize("hasAuthority('article:save')")
    public Result<Map<String, Object>> create(@AuthenticationPrincipal LoginUser me,
                                              @Valid @RequestBody ArticleSaveDTO dto) {
        return Result.ok(articleService.create(me, dto));
    }

    /** PUT /api/articles/{id} —— 更新（携带 version 乐观锁；作者或 ADMIN） */
    @Operation(summary = "更新文章")
    @PutMapping("/{id}")
    @org.springframework.security.access.prepost.PreAuthorize("hasAuthority('article:save')")
    public Result<Map<String, Object>> update(@AuthenticationPrincipal LoginUser me,
                                              @PathVariable Long id,
                                              @Valid @RequestBody ArticleSaveDTO dto) {
        return Result.ok(articleService.update(me, id, dto));
    }

    /** PATCH /api/articles/{id}/status —— 状态流转：发布/下线/回草稿 */
    @Operation(summary = "文章状态流转")
    @PatchMapping("/{id}/status")
    @org.springframework.security.access.prepost.PreAuthorize("hasAuthority('article:save')")
    public Result<Void> changeStatus(@AuthenticationPrincipal LoginUser me,
                                     @PathVariable Long id,
                                     @Valid @RequestBody ArticleStatusDTO dto) {
        String message = articleService.changeStatus(me, id, dto.getStatus());
        // 用 Result 的自定义 message 通道回显动作文案（"已发布/已下线/已回到草稿"，文档 §4 示例）
        return new Result<>(0, message, null, System.currentTimeMillis());
    }

    /**
     * PATCH /api/articles/{id}/top —— 置顶开关（ADMIN，UC-A-02）。
     * 扩展路径：文档 §4 未列置顶接口，但 UC-A-02 明确"管理员可置顶任意文章"，
     * 治理动作需要 HTTP 入口；语义与状态流转同构（PATCH + 窄入参），
     * 权限点复用 article:save + hasRole('ADMIN') 双条件——不新增权限码，
     * 避免种子/设计文档/实体三方漂移（台账已备注该设计决策）。
     */
    @Operation(summary = "置顶/取消置顶（管理员）")
    @PatchMapping("/{id}/top")
    @org.springframework.security.access.prepost.PreAuthorize("hasRole('ADMIN')")
    public Result<Void> setTop(@PathVariable Long id, @RequestParam Integer topFlag) {
        if (topFlag == null || (topFlag != 0 && topFlag != 1)) {
            return Result.fail(com.blog.common.BizCode.PARAM_ERROR, "topFlag 仅接受 0/1");
        }
        articleService.setTop(id, topFlag);
        return Result.ok();
    }

    /** DELETE /api/articles/{id} —— 逻辑删除（作者或 ADMIN） */
    @Operation(summary = "删除文章（逻辑删除）")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@AuthenticationPrincipal LoginUser me,
                               @PathVariable Long id) {
        articleService.delete(me, id);
        return Result.ok();
    }

    /**
     * GET /api/articles/archive —— 归档树。
     *
     * <p>路径次序陷阱必须说明：Spring MVC 按"精确段优先于模板段"匹配，
     * /articles/archive 不会误落入 /articles/{id}（{id} 声明的是 Long，
     * archive 转 Long 失败会先命中更具体模式）；顺序声明在 detail 之后亦可，
     * 这里按文档 §4 的表格次序排布，保持"代码即文档"的可对照性。
     */
    @Operation(summary = "归档树（年→月→文章）")
    @GetMapping("/archive")
    public Result<Map<String, Map<String, java.util.List<ArticleVO>>>> archive() {
        return Result.ok(articleService.archive());
    }

    /**
     * PATCH /api/articles/{id}/offline —— 管理员强制下线（UC-A-02）。
     * 与作者自服下线分口的理由：入口审计语义不同（谁干的），
     * Service 层也走独立方法（不带属主校验、带 ADMIN 注解），权限面清晰不重叠。
     */
    @Operation(summary = "强制下线（管理员）")
    @PatchMapping("/{id}/offline")
    @org.springframework.security.access.prepost.PreAuthorize("hasRole('ADMIN')")
    public Result<Void> forceOffline(@PathVariable Long id) {
        String message = articleService.forceOfflineByAdmin(id);
        return new Result<>(0, message, null, System.currentTimeMillis());
    }

    // ==================== M10 回收站（UC-U-09"管理员可在回收站彻底删除"） ====================
    // 三接口全部 hasRole('ADMIN')：回收站是全站治理视图，任何普通用户（含文章属主）
    // 都不许读已删行——"自己的文章自己恢复"看似无害，但删除动作可能来自管理员
    // 治理（下线违规文后删除），让用户自助复活等于架空治理链。恢复入口只放后台。

    /** GET /api/articles/trash —— 回收站分页（仅 ADMIN；精确段优先于 /{id} 模板，勿与 detail 混淆） */
    @Operation(summary = "回收站分页（管理员）")
    @GetMapping("/trash")
    @org.springframework.security.access.prepost.PreAuthorize("hasRole('ADMIN')")
    public Result<PageResult<ArticleVO>> trash(@RequestParam(defaultValue = "1") long pageNum,
                                               @RequestParam(defaultValue = "10") long pageSize,
                                               @RequestParam(required = false) String keyword) {
        return Result.ok(articleService.pageTrash(keyword,
                Math.max(1, pageNum), Math.min(Math.max(1, pageSize), 100)));
    }

    /** POST /api/articles/{id}/restore —— 从回收站恢复（仅 ADMIN，幂等） */
    @Operation(summary = "从回收站恢复文章（管理员）")
    @PostMapping("/{id}/restore")
    @org.springframework.security.access.prepost.PreAuthorize("hasRole('ADMIN')")
    public Result<Void> restore(@PathVariable Long id) {
        String message = articleService.restoreFromTrash(id);
        return new Result<>(0, message, null, System.currentTimeMillis());
    }

    /** DELETE /api/articles/{id}/purge —— 彻底删除（仅 ADMIN，物理删行，三重护栏见 Service 注释） */
    @Operation(summary = "彻底删除回收站文章（管理员）")
    @DeleteMapping("/{id}/purge")
    @org.springframework.security.access.prepost.PreAuthorize("hasRole('ADMIN')")
    public Result<Void> purge(@PathVariable Long id) {
        articleService.purgeFromTrash(id);
        return Result.ok();
    }
}
