package com.blog.module.user.controller;

import com.blog.common.PageResult;
import com.blog.common.Result;
import com.blog.module.article.service.ArticleService;
import com.blog.module.article.vo.ArticleVO;
import com.blog.module.user.dto.ChangePasswordDTO;
import com.blog.module.user.dto.ProfileUpdateDTO;
import com.blog.module.user.service.UserService;
import com.blog.module.user.vo.UserVO;
import com.blog.security.LoginUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 用户自助接口（M2）—— 路径对齐《API接口设计.md》§3。
 * 鉴权口径：全部要求登录（authenticated()），"作者(本人)"约束在 Service
 * 用登录态 id 做 WHERE 条件实现（越权三防第三层），GET 查他人只回公开字段。
 */
@Tag(name = "用户", description = "资料/改密/头像")
@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;
    /** M3：作者公开文章列表复用文章服务（跨模块只依赖 Service 层，符合系统设计调用链约定） */
    private final ArticleService articleService;

    /** GET /api/users/profile?userId=（缺省为自己；查他人仅公开字段） */
    @Operation(summary = "查看资料")
    @GetMapping("/profile")
    public Result<UserVO> profile(@AuthenticationPrincipal LoginUser me,
                                  @RequestParam(required = false) Long userId) {
        return Result.ok(userService.getProfile(me, userId));
    }

    /** PUT /api/users/profile：修改昵称/简介/邮箱（仅本人） */
    @Operation(summary = "修改资料")
    @PutMapping("/profile")
    public Result<UserVO> updateProfile(@AuthenticationPrincipal LoginUser me,
                                        @Valid @RequestBody ProfileUpdateDTO dto) {
        return Result.ok(userService.updateProfile(me, dto));
    }

    /**
     * PUT /api/users/password：改密 + 全端强制下线。
     * message 用文档约定文案"密码已更新，请重新登录"——本接口返回后当前令牌
     * 已失效，前端据 message 引导重登（data 为空）。
     */
    @Operation(summary = "修改密码（改后全端下线）")
    @PutMapping("/password")
    public Result<Void> changePassword(@AuthenticationPrincipal LoginUser me,
                                       @Valid @RequestBody ChangePasswordDTO dto) {
        userService.changePassword(me, dto);
        return new Result<>(0, "密码已更新，请重新登录", null, System.currentTimeMillis());
    }

    /** POST /api/users/avatar：multipart file 上传头像，回传更新后的资料（含新 avatar URL） */
    @Operation(summary = "上传头像")
    @PostMapping("/avatar")
    public Result<UserVO> avatar(@AuthenticationPrincipal LoginUser me,
                                 @RequestParam("file") MultipartFile file) {
        return Result.ok(userService.uploadAvatar(me, file));
    }

    /**
     * GET /api/users/{id}/articles（M3 补全，文档 §3 用户模块最后一行）：
     * 某作者的公开文章——用户主页"TA 的文章"入口。
     * 公开接口（SecurityConfig 已放行 GET），Service 层强制 status=1 过滤，
     * 游客永远看不到目标的草稿（可见性矩阵与文章详情同一把尺子）。
     */
    @Operation(summary = "某作者的公开文章")
    @GetMapping("/{id}/articles")
    public Result<PageResult<ArticleVO>> authorArticles(@PathVariable Long id,
                                                        @RequestParam(defaultValue = "1") long pageNum,
                                                        @RequestParam(defaultValue = "10") long pageSize) {
        return Result.ok(articleService.authorArticles(id,
                Math.max(1, pageNum), Math.min(Math.max(1, pageSize), 100)));
    }
}
