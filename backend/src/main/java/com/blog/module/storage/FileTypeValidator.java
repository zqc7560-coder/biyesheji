package com.blog.module.storage;

import java.util.Locale;
import java.util.Set;

/**
 * 图片文件类型校验器（M8）—— 扩展名白名单 + 魔数（文件头签名）双维校验的纯函数集。
 *
 * <p>为什么单独抽出这个类：M2 头像链路只做了"扩展名 + Content-Type"两卡，但
 * Content-Type 完全由客户端自报（curl -H 一行就能伪造），而上传通道 M8 起对全体
 * 登录用户开放正文插图/封面，攻击面从小变小、"把 HTML/SVG 伪装成 png 传上去，
 * 再通过 /uploads 静态直出执行"就是存储型 XSS 的经典路径。文件头魔数是浏览器
 * 之外唯一的事实来源，且校验成本只有 12 字节 IO——所以在这里补上第三卡。
 *
 * <p>纯 static 函数、零 Spring 依赖：可被 MinimalFileStorageService 与单测直接调用，
 * 不需要拉起重型上下文就能锁死安全规则（安全测试清单条目，值得最快的回归成本）。
 */
public final class FileTypeValidator {

    /**
     * 业务类型白名单（《API接口设计.md》§8 POST /api/files/upload 的 type 参数）：
     * avatar 头像 / cover 文章封面 / article 正文插图。
     * 本期三类共用同一套图片规则与同一存储根目录（§3.13 file 表不区分业务目录），
     * 但枚举仍要收口——脏 type 落进 file 表没有任何清理策略能接住它。
     */
    public static final Set<String> BIZ_TYPES = Set.of("avatar", "cover", "article");

    /** 扩展名白名单：与设计文档 §3.13"jpg/png/webp/gif"一致（jpeg 与 jpg 同义都收） */
    public static final Set<String> IMAGE_EXT_WHITELIST = Set.of("png", "jpg", "jpeg", "webp", "gif");

    private FileTypeValidator() {
    }

    /** 取小写扩展名（无扩展名或 ".结尾" 返回空串，交给白名单拒绝） */
    public static String extensionOf(String filename) {
        if (filename == null) {
            return "";
        }
        int dot = filename.lastIndexOf('.');
        if (dot < 0 || dot == filename.length() - 1) {
            return "";
        }
        return filename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /** 扩展名 → 入库 MIME（file.file_type 列 VARCHAR(20)）；非白名单返回 null */
    public static String mimeOf(String ext) {
        return switch (ext) {
            case "png" -> "image/png";
            case "jpg", "jpeg" -> "image/jpeg";
            case "webp" -> "image/webp";
            case "gif" -> "image/gif";
            default -> null;
        };
    }

    /**
     * 魔数校验：读文件头若干字节比对签名。
     *
     * <p>可接受的字节数下界：gif 签名 6 字节最短，webp 需要 RIFF(4)+size(4)+WEBP(4)=12
     * 字节最长——调用方按 12 字节喂即可。返回归一化扩展名（"jpeg" 统一成 "jpg" 之外
     * 保持原签名族命名），无法识别返回 null。
     *
     * <p>为什么 webp 也查 WEBP 标记而不只查 RIFF：RIFF 头同时是 .wav/.avi 的容器头，
     * 只查 RIFF 会把音频文件放进图片白名单。
     */
    public static String sniffExtension(byte[] header) {
        if (header == null) {
            return null;
        }
        // PNG: 89 50 4E 47 0D 0A 1A 0A（八字节完整签名，最严格的族）
        if (startsWith(header, 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A)) {
            return "png";
        }
        // JPEG: FF D8 FF（前缀签名；FF D8 单独用会误收残缺文件，FF D8 FF 才是 JFIF 常态）
        if (startsWith(header, 0xFF, 0xD8, 0xFF)) {
            return "jpg";
        }
        // GIF: 'GIF87a' / 'GIF89a'
        if (startsWith(header, 'G', 'I', 'F', '8', '7', 'a')
                || startsWith(header, 'G', 'I', 'F', '8', '9', 'a')) {
            return "gif";
        }
        // WEBP: RIFF????WEBP —— 4~8 字节是 LE 容器长度，跳过比对
        if (header.length >= 12
                && header[0] == 'R' && header[1] == 'I' && header[2] == 'F' && header[3] == 'F'
                && header[8] == 'W' && header[9] == 'E' && header[10] == 'B' && header[11] == 'P') {
            return "webp";
        }
        return null;
    }

    /** 变参 int 便于书写 0x89/'P' 混合字面量；负数比对时按字节低 8 位掩码归一 */
    private static boolean startsWith(byte[] data, int... signature) {
        if (data.length < signature.length) {
            return false;
        }
        for (int i = 0; i < signature.length; i++) {
            if ((data[i] & 0xFF) != (signature[i] & 0xFF)) {
                return false;
            }
        }
        return true;
    }
}
