package com.blog.module.storage.controller;

import com.blog.common.BizCode;
import com.blog.common.BizException;
import com.blog.common.Result;
import com.blog.module.storage.FileTypeValidator;
import com.blog.module.storage.entity.FileRecord;
import com.blog.module.storage.service.MinimalFileStorageService;
import com.blog.security.LoginUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 通用文件上传接口（M8）—— 对齐《API接口设计.md》§8：
 * POST /api/files/upload，multipart 参数 file + type(article/cover/avatar)，
 * 响应 data = {url, fileId, size}。
 *
 * <p>与 M2 的 POST /api/users/avatar 的关系：头像接口保留（它是"上传+回写
 * sys_user.avatar"的原子动作，拆开会让前端多一次 PUT），而本接口是纯存储通道，
 * 服务编辑器正文插图与封面图（前端拿到 url 自行回填表单，随文章保存生效）。
 * 两通道共用同一存储服务——校验规则只有一份，不存在"头像严、正文松"的口径漂移。
 *
 * <p>权限口径：仅要求登录（SecurityConfig 的 anyRequest().authenticated() 默认闸），
 * 不挂权限点——UC-U-04（登录用户写作）天然包含插图，给"能发文"之外的用户传图
 * 没有业务场景，但也没必要加权限点拦自己人（上传物只有链接被引用才可见）。
 */
@Tag(name = "文件", description = "通用图片上传")
@RestController
@RequestMapping("/api/files")
@RequiredArgsConstructor
public class FileController {

    private final MinimalFileStorageService storageService;

    /**
     * POST /api/files/upload。
     *
     * <p>type 白名单校验放在 Controller 层：存储服务对 bizType 只做日志标记
     * （三类的存储规则完全相同），把它做成存储参数反而是虚假耦合——这里挡脏值
     * 是为了 file 表数据可治理（按 bizType 查日志/未来按业务清理）。
     */
    @Operation(summary = "上传图片（≤5MB，白名单 png/jpg/webp/gif，魔数校验）")
    @PostMapping("/upload")
    public Result<Map<String, Object>> upload(@AuthenticationPrincipal LoginUser me,
                                              @RequestParam("file") MultipartFile file,
                                              @RequestParam(value = "type", required = false,
                                                      defaultValue = "article") String type) {
        String biz = type == null ? "" : type.trim().toLowerCase(Locale.ROOT);
        if (!FileTypeValidator.BIZ_TYPES.contains(biz)) {
            throw new BizException(BizCode.PARAM_ERROR,
                    "type 仅支持 " + String.join("/", FileTypeValidator.BIZ_TYPES));
        }
        FileRecord record = storageService.store(file, biz, me.getUserId());

        // 响应结构逐字段对齐 API 文档 §8 示例：{url, fileId, size}
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("url", record.getUrl());
        data.put("fileId", record.getId());
        data.put("size", record.getFileSize());
        return Result.ok(data);
    }
}
