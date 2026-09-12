package com.blog.module.storage.service;

import com.blog.common.BizCode;
import com.blog.common.BizException;
import com.blog.module.storage.entity.FileRecord;
import com.blog.module.storage.mapper.FileRecordMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 最小图片存储服务（M2 按需先行版）—— 头像上传依赖它，通用文件接口 M8 扩展复用。
 * 规则蓝本：《API接口设计.md》§8 POST /api/files/upload（≤5MB、图片白名单、
 * uploads/yyyy/MM/uuid.ext 重命名）与《数据库设计.md》§3.13 file 表。
 *
 * <p>为什么"重命名 + 按年月分目录"而不用原始文件名落盘：
 * 1) 原始名可含 ../../ 或特殊字符，直接拼接是路径穿越漏洞（安全测试清单项）；
 * 2) uuid 名天然防同名覆盖与并发冲突（uk_stored 唯一键也不会有竞争）；
 * 3) 分目录防单目录文件数过大拖垮文件系统（ext4 默认目录线性查找）。
 * 原始文件名保留在 file.original_name 列，仅作下载展示名。
 *
 * <p>白名单用"扩展名 + MIME 双卡"：扩展名可伪造，Content-Type 由浏览器根据
 * 魔数嗅探（现代浏览器行为），两关都过才收。真·魔数校验（读文件头签名）列
 * 入 M8 完善项，本期头像链路风险面小（仅本人可见）可接受。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MinimalFileStorageService {

    /** 图片扩展名白名单（与 API 文档 image/png,jpeg,webp,gif 对齐；jpeg/jpg 同义都收） */
    private static final Set<String> IMAGE_EXT_WHITELIST = Set.of("png", "jpg", "jpeg", "webp", "gif");

    /** 扩展名 → 存储的 MIME 主类型（file.file_type 列 VARCHAR(20)） */
    private static final Map<String, String> EXT_MIME = Map.of(
            "png", "image/png", "jpg", "image/jpeg", "jpeg", "image/jpeg",
            "webp", "image/webp", "gif", "image/gif");

    /** 大小上限：3002 错误码语义 = 5MB（API 文档 §1.4 文件区段） */
    private static final long MAX_SIZE = 5L * 1024 * 1024;

    private static final DateTimeFormatter DIR_FMT = DateTimeFormatter.ofPattern("yyyy/MM");

    /** 落盘根目录：与 application.yml blog.upload.dir 同源（dev 默认 ./uploads，
     *  prod 挂载卷 /app/uploads），WebMvcConfig 的静态映射用同一配置键 */
    @Value("${blog.upload.dir:./uploads}")
    private String uploadDir;

    private final FileRecordMapper fileRecordMapper;

    /**
     * 存储一张图片并登记 file 表，返回可访问 URL（/uploads/yyyy/MM/uuid.ext）。
     *
     * @param bizType 业务归类（avatar/article/cover），仅入日志与路径注释用，
     *                本期不分目录（M8 若需按业务清理再演进）
     */
    public String storeImage(MultipartFile file, String bizType, Long userId) {
        if (file == null || file.isEmpty()) {
            throw new BizException(BizCode.PARAM_ERROR, "文件为空");
        }
        if (file.getSize() > MAX_SIZE) {
            throw new BizException(BizCode.FILE_TOO_LARGE);
        }

        String original = file.getOriginalFilename() == null ? "" : file.getOriginalFilename();
        String ext = extensionOf(original);
        if (!IMAGE_EXT_WHITELIST.contains(ext)) {
            throw new BizException(BizCode.FILE_TYPE_INVALID,
                    "仅支持图片格式: " + String.join("/", IMAGE_EXT_WHITELIST));
        }
        String contentType = file.getContentType();
        if (contentType == null || !contentType.toLowerCase(Locale.ROOT).startsWith("image/")) {
            // 双卡第二关：MIME 主类型必须是 image/*（伪造 png 扩展名但 content-type 是
            // text/html 的上传在这里被拒，配合生产 Nginx 对 /uploads 加 nosniff 头）
            throw new BizException(BizCode.FILE_TYPE_INVALID, "文件内容类型非图片");
        }

        // 落盘：uploads/yyyy/MM/uuid.ext —— 全新生成名，与用户输入零关联（防穿越）
        String relative = LocalDate.now().format(DIR_FMT) + "/"
                + UUID.randomUUID().toString().replace("-", "") + "." + ext;
        Path target = Paths.get(uploadDir).toAbsolutePath().normalize().resolve(relative);
        try {
            Files.createDirectories(target.getParent());
            file.transferTo(target);
        } catch (IOException e) {
            log.error("图片落盘失败 biz={} userId={}", bizType, userId, e);
            throw new BizException(BizCode.SYSTEM_ERROR, "文件保存失败");
        }

        // 登记 file 表（url 用正斜杠相对路径，Nginx/静态映射两环境同值）
        FileRecord record = new FileRecord();
        record.setUserId(userId);
        record.setOriginalName(original.length() > 255 ? original.substring(0, 255) : original);
        record.setStoredPath("uploads/" + relative);
        record.setUrl("/uploads/" + relative);
        record.setFileType(EXT_MIME.get(ext));
        record.setFileSize(file.getSize());
        record.setStorageType(0); // 0 本地磁盘（1 OSS 列预留，本期不做）
        fileRecordMapper.insert(record);

        return record.getUrl();
    }

    /** 取小写扩展名（无扩展名返回空串，交给白名单拒绝） */
    private String extensionOf(String filename) {
        int dot = filename.lastIndexOf('.');
        if (dot < 0 || dot == filename.length() - 1) {
            return "";
        }
        return filename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
