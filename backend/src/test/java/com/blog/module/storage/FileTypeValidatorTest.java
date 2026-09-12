package com.blog.module.storage;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 图片类型校验器单测（M8 安全红线组件）—— 不拉 Spring 上下文：
 * 安全规则值得最快的回归成本（《部署与测试.md》§3 安全用例清单的执行载体之一）。
 *
 * <p>魔数用例全部用"真签名字节 + 垃圾尾"构造：校验器设计上只看头 12 字节，
 * 无需真图片文件；同时反向用例（HTML 换皮 .png、扩展名与族不符）必须覆盖——
 * 那正是 /uploads 静态直出的存储型 XSS 攻击形态。
 */
@DisplayName("M8 文件类型校验器: 白名单/魔数/同族")
class FileTypeValidatorTest {

    // ==================== 扩展名 ====================

    @Test
    @DisplayName("扩展名归一化: 大小写不敏感、无点/尾点回空串")
    void extensionNormalization() {
        assertThat(FileTypeValidator.extensionOf("Photo.PNG")).isEqualTo("png");
        assertThat(FileTypeValidator.extensionOf("no_ext")).isEmpty();
        assertThat(FileTypeValidator.extensionOf("trailing.")).isEmpty();
        assertThat(FileTypeValidator.extensionOf(null)).isEmpty();
        // 双扩展名取末段：payload.png.html 的"末段 html"必被白名单拒——
        // 这是"复合扩展名绕过"的经典形态，取末段而不是首段才封得住
        assertThat(FileTypeValidator.extensionOf("payload.png.html")).isEqualTo("html");
    }

    @ParameterizedTest
    @CsvSource({"png,image/png", "jpg,image/jpeg", "jpeg,image/jpeg",
                "webp,image/webp", "gif,image/gif", "svg,__NULL__"})
    @DisplayName("mimeOf 映射与 VARCHAR(20) 列宽对齐; 非白名单回 null")
    void mimeMapping(String ext, String expected) {
        // CSV 空列会被解析成真 null 直接 NPE（首轮实测），用哨兵串表达"期望 null"
        assertThat(FileTypeValidator.mimeOf(ext))
                .isEqualTo("__NULL__".equals(expected) ? null : expected);
    }

    // ==================== 魔数嗅探 ====================

    private static byte[] pngHead() {
        return new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0};
    }

    @Test
    @DisplayName("PNG 完整 8 字节签名识别")
    void sniffPng() {
        assertThat(FileTypeValidator.sniffExtension(pngHead())).isEqualTo("png");
    }

    @Test
    @DisplayName("JPEG FF D8 FF 前缀识别")
    void sniffJpeg() {
        byte[] head = new byte[12];
        head[0] = (byte) 0xFF; head[1] = (byte) 0xD8; head[2] = (byte) 0xFF;
        assertThat(FileTypeValidator.sniffExtension(head)).isEqualTo("jpg");
    }

    @Test
    @DisplayName("GIF87a/GIF89a 识别; 截断到 5 字节不认（防半吊子伪装）")
    void sniffGif() {
        assertThat(FileTypeValidator.sniffExtension("GIF89a".getBytes())).isEqualTo("gif");
        assertThat(FileTypeValidator.sniffExtension(new byte[]{'G', 'I', 'F', '8'})).isNull();
    }

    @Test
    @DisplayName("WEBP 须 RIFF+WEBP 双标记: 只给 RIFF 头（wav/avi 形态）不认")
    void sniffWebp() {
        byte[] webp = new byte[12];
        System.arraycopy("RIFF".getBytes(), 0, webp, 0, 4);
        System.arraycopy("WEBP".getBytes(), 0, webp, 8, 4);
        assertThat(FileTypeValidator.sniffExtension(webp)).isEqualTo("webp");

        byte[] riffOnly = new byte[12];
        System.arraycopy("RIFF".getBytes(), 0, riffOnly, 0, 4);
        System.arraycopy("WAVE".getBytes(), 0, riffOnly, 8, 4);
        assertThat(FileTypeValidator.sniffExtension(riffOnly)).isNull();
    }

    @Nested
    @DisplayName("攻击形态样本必须嗅探失败")
    class MaliciousSamples {

        @Test
        @DisplayName("HTML 脚本换皮 png：头字节是 '<' 不是 0x89 → null")
        void htmlDisguise() {
            byte[] fake = "<script>alert(1)</script>".getBytes();
            assertThat(FileTypeValidator.sniffExtension(fake)).isNull();
        }

        @Test
        @DisplayName("SVG 明文开头（<svg）：扩展名白名单没有 svg，魔数也不认任何一族 → null")
        void svgDisguise() {
            assertThat(FileTypeValidator.sniffExtension("<svg onload=x>".getBytes())).isNull();
        }

        @Test
        @DisplayName("空/超短输入与 null 防御：回 null 而不是抛越界")
        void shortInputs() {
            assertThat(FileTypeValidator.sniffExtension(new byte[0])).isNull();
            assertThat(FileTypeValidator.sniffExtension(new byte[]{(byte) 0x89, 'P'})).isNull();
            assertThat(FileTypeValidator.sniffExtension(null)).isNull();
        }
    }

    // ==================== 业务类型白名单 ====================

    @Test
    @DisplayName("bizType 白名单 = API 文档 §8 三值（avatar/cover/article）")
    void bizTypes() {
        assertThat(FileTypeValidator.BIZ_TYPES).containsExactlyInAnyOrder("avatar", "cover", "article");
    }
}
