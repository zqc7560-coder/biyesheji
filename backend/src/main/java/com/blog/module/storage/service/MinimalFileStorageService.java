package com.blog.module.storage.service;

import com.blog.common.BizCode;
import com.blog.common.BizException;
import com.blog.module.storage.FileTypeValidator;
import com.blog.module.storage.entity.FileRecord;
import com.blog.module.storage.mapper.FileRecordMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.UUID;

/**
 * 图片存储服务（M2 先行版 → M8 全站通用通道）。
 * 规则蓝本：《API接口设计.md》§8 POST /api/files/upload（≤5MB、图片白名单、
 * uploads/yyyy/MM/uuid.ext 重命名）与《数据库设计.md》§3.13 file 表。
 *
 * <p>为什么"重命名 + 按年月分目录"而不用原始文件名落盘：
 * 1) 原始名可含 ../../ 或特殊字符，直接拼接是路径穿越漏洞（安全测试清单项）；
 * 2) uuid 名天然防同名覆盖与并发冲突（uk_stored 唯一键也不会有竞争）；
 * 3) 分目录防单目录文件数过大拖垮文件系统（ext4 默认目录线性查找）。
 * 原始文件名保留在 file.original_name 列，仅作下载展示名。
 *
 * <p>M8 三卡收口（M2 只有前两卡）：
 * ① 扩展名白名单（png/jpg/jpeg/webp/gif）；
 * ② Content-Type 主类型 image/*（浏览器行为约束，可被 curl 伪造——只是第一道噪音过滤）；
 * ③ 魔数校验（读文件头 12 字节比对 PNG/JPEG/GIF/WEBP 签名，且须与扩展名同族）——
 * 台账 M2 记的"真·魔数校验列入 M8"在此落地。动机：/uploads 是静态直出目录，
 * 一张"改名成 png 的 HTML/SVG"若入库即成存储型 XSS 跳板，魔数是唯一不可伪造的自证。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MinimalFileStorageService {

    /** 大小上限：3002 错误码语义 = 5MB（API 文档 §1.4 文件区段） */
    private static final long MAX_SIZE = 5L * 1024 * 1024;

    /** 魔数嗅探读取的头部字节数：WEBP 需要 12 字节（RIFF+size+WEBP），取最长需求 */
    private static final int MAGIC_BYTES = 12;

    private static final DateTimeFormatter DIR_FMT = DateTimeFormatter.ofPattern("yyyy/MM");

    /** 落盘根目录：与 application.yml blog.upload.dir 同源（dev 默认 ./uploads，
     *  prod 挂载卷 /app/uploads），WebMvcConfig 的静态映射用同一配置键 */
    @Value("${blog.upload.dir:./uploads}")
    private String uploadDir;

    private final FileRecordMapper fileRecordMapper;

    /**
     * 存储一张图片并登记 file 表，返回落盘后的完整记录（M8 通用通道用：
     * /api/files/upload 需要把 fileId/url/size 三字段都回给前端）。
     *
     * @param bizType 业务归类（avatar/cover/article），本期共用同一存储根目录，
     *                仅入日志备查（§3.13 file 表无业务列，按业务分目录是 P2 清理演进项）
     */
    public FileRecord store(MultipartFile file, String bizType, Long userId) {
        if (file == null || file.isEmpty()) {
            throw new BizException(BizCode.PARAM_ERROR, "文件为空");
        }
        if (file.getSize() > MAX_SIZE) {
            throw new BizException(BizCode.FILE_TOO_LARGE);
        }

        // ---------- 三卡校验（顺序：便宜的前、贵的后，全部通过才碰磁盘） ----------
        String original = file.getOriginalFilename() == null ? "" : file.getOriginalFilename();
        String ext = FileTypeValidator.extensionOf(original);
        if (!FileTypeValidator.IMAGE_EXT_WHITELIST.contains(ext)) {
            throw new BizException(BizCode.FILE_TYPE_INVALID,
                    "仅支持图片格式: " + String.join("/", FileTypeValidator.IMAGE_EXT_WHITELIST));
        }
        String contentType = file.getContentType();
        if (contentType == null || !contentType.toLowerCase(Locale.ROOT).startsWith("image/")) {
            // 双卡第二关：MIME 主类型必须是 image/*（伪造 png 扩展名但 content-type 是
            // text/html 的上传在这里被拒，配合生产 Nginx 对 /uploads 加 nosniff 头）
            throw new BizException(BizCode.FILE_TYPE_INVALID, "文件内容类型非图片");
        }
        // 第三卡（M8 新增）：文件头魔数嗅探，且必须与扩展名同族
        String sniffed = sniffMagic(file);
        if (sniffed == null) {
            // 头 12 字节对不上任何白名单图片签名——典型的"文本/脚本换皮成 .png"
            throw new BizException(BizCode.FILE_TYPE_INVALID, "文件内容与图片格式不符（魔数校验失败）");
        }
        if (!sameImageFamily(ext, sniffed)) {
            // 扩展名 .jpg 实为 PNG 数据这类"族不一致"同样拒收：落盘后缀决定 Content-Type
            // 响应头，跨族文件会让下游按错误类型解析（浏览器宽容解析是 XSS 温床）
            throw new BizException(BizCode.FILE_TYPE_INVALID, "扩展名与文件实际格式不符");
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
        record.setFileType(FileTypeValidator.mimeOf(ext));
        record.setFileSize(file.getSize());
        record.setStorageType(0); // 0 本地磁盘（1 OSS 列预留，本期不做）
        fileRecordMapper.insert(record);

        log.info("图片上传成功 biz={} userId={} fileId={} url={}", bizType, userId,
                record.getId(), record.getUrl());
        return record;
    }

    /**
     * 存图并只回 URL（M2 头像链路的稳定门面）。
     * UserService 只消费 URL 不关心 fileId，保留旧签名避免上游无谓改动。
     */
    public String storeImage(MultipartFile file, String bizType, Long userId) {
        return store(file, bizType, userId).getUrl();
    }

    /** 读文件头魔数（独立方法便于单测替身）；IO 失败按"非法文件"拒收而非放行 */
    private String sniffMagic(MultipartFile file) {
        byte[] head = new byte[MAGIC_BYTES];
        try (InputStream in = file.getInputStream()) {
            int read = 0;
            while (read < MAGIC_BYTES) {
                int n = in.read(head, read, MAGIC_BYTES - read);
                if (n < 0) {
                    break; // 短文件（如测试里 7 字节 png 头）：读到 EOF，按已读部分比对
                }
                read += n;
            }
        } catch (IOException e) {
            log.warn("读取上传文件头失败，按非法文件处理: {}", e.getMessage());
            return null;
        }
        return FileTypeValidator.sniffExtension(head);
    }

    /** 扩展名与嗅探结果是否同族：jpg/jpeg 互为同义，其余要求严格相等 */
    private boolean sameImageFamily(String declaredExt, String sniffed) {
        String a = declaredExt.equals("jpeg") ? "jpg" : declaredExt;
        return a.equals(sniffed);
    }
}
