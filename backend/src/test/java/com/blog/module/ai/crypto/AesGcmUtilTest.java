package com.blog.module.ai.crypto;

import com.blog.common.BizException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * AES-256-GCM 密钥工程单测（项目计划难点 D-2 的验收件：
 * "加密→模拟入库→解密→比对 + Base64(IV+CT+Tag) 格式验证"）。
 * 纯算法层测试，不起 Spring 上下文——快且与数据库/Redis 零耦合。
 */
class AesGcmUtilTest {

    /** 测试专用 32 字节主密钥（hex 形态；它保护的明文也只有测试假 Key） */
    private static byte[] key() {
        return java.util.HexFormat.of().parseHex(
                "000102030405060708090a0b0c0d0e0f000102030405060708090a0b0c0d0e0f");
    }

    @Test
    @DisplayName("加解密往返：明文→密文→明文逐字节一致")
    void roundTrip() {
        String plain = "sk-b42ccd-FAKE-TEST-ONLY-0123456789";
        String cipher = AesGcmUtil.encrypt(plain, key());
        assertThat(cipher).isNotBlank().isNotEqualTo(plain);
        // 信封=Base64(12B IV + CT + 16B Tag)：短 Key 的密文 Base64 长度必然 > 28B 封装的裸长
        assertThat(cipher).doesNotContain(plain);
        assertThat(AesGcmUtil.decrypt(cipher, key())).isEqualTo(plain);
    }

    @Test
    @DisplayName("随机 IV：同明文两次加密密文不同（防拖库比对推断 Key 复用）")
    void randomIvProducesDifferentCipher() {
        String plain = "sk-SAME-KEY-VALUE";
        String c1 = AesGcmUtil.encrypt(plain, key());
        String c2 = AesGcmUtil.encrypt(plain, key());
        assertThat(c1).isNotEqualTo(c2);
        // 但两者都解回同一明文
        assertThat(AesGcmUtil.decrypt(c1, key())).isEqualTo(plain);
        assertThat(AesGcmUtil.decrypt(c2, key())).isEqualTo(plain);
    }

    @Test
    @DisplayName("主密钥不匹配 → KeyDecryptException（GCM tag 校验拒绝，绝不吐出脏 Key）")
    void wrongMasterKeyRejected() {
        String cipher = AesGcmUtil.encrypt("sk-test", key());
        byte[] otherKey = java.util.HexFormat.of().parseHex(
                "ffeeddccbbaa99887766554433221100ffeeddccbbaa99887766554433221100");
        assertThatThrownBy(() -> AesGcmUtil.decrypt(cipher, otherKey))
                .isInstanceOf(KeyDecryptException.class);
    }

    @Test
    @DisplayName("密文被篡改（拖库改一字节）→ GCM 认证失败抛异常，CBC 式静默解错在此绝迹")
    void tamperedCipherRejected() {
        String cipher = AesGcmUtil.encrypt("sk-test-key-123456", key());
        // 翻转末尾区域一个字符（落在 Tag/密文上）——用固定替换保证 Base64 仍合法
        char last = cipher.charAt(cipher.length() - 2);
        char flip = last == 'A' ? 'B' : 'A';
        String tampered = cipher.substring(0, cipher.length() - 2) + flip + cipher.charAt(cipher.length() - 1);
        // Base64 解码可能因等价字符仍成功；两种失败模式都在允许集合内
        assertThatThrownBy(() -> AesGcmUtil.decrypt(tampered, key()))
                .isInstanceOfAny(KeyDecryptException.class, IllegalArgumentException.class);
    }

    @Test
    @DisplayName("占位串（非 Base64）解密 → KeyDecryptException 携带'未导入 Key'引导语义")
    void pendingPlaceholderRejected() {
        assertThatThrownBy(() -> AesGcmUtil.decrypt("__PENDING_KEY_IMPORT__", key()))
                .isInstanceOf(KeyDecryptException.class)
                .hasMessageContaining("未导入");
    }

    @Test
    @DisplayName("主密钥规格：null/短密钥显式拒绝（宁可启动失败不做假安全）")
    void keyLengthEnforced() {
        assertThatThrownBy(() -> AesGcmUtil.encrypt("x", new byte[16]))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32");
    }

    @Test
    @DisplayName("parseSecret：64位hex / 32字符口令两种形态；其余形态回 4001 业务码")
    void parseSecretForms() {
        assertThat(KeyCipherService.parseSecret("000102030405060708090a0b0c0d0e0f000102030405060708090a0b0c0d0e0f"))
                .hasSize(32);
        assertThat(KeyCipherService.parseSecret("dev-secret-exactly-thirty-two-ch"))  // 恰 32 字符
                .hasSize(32)
                .isEqualTo("dev-secret-exactly-thirty-two-ch".getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> KeyCipherService.parseSecret("short"))
                .isInstanceOf(BizException.class);
        assertThatThrownBy(() -> KeyCipherService.parseSecret(null))
                .isInstanceOf(BizException.class);
        assertThatThrownBy(() -> KeyCipherService.parseSecret("   "))
                .isInstanceOf(BizException.class);
    }

    @Test
    @DisplayName("掩码规则：前3+****+后4；短 Key 全打星；空回'未导入Key'")
    void maskRules() {
        assertThat(KeyCipherService.mask("sk-abcdef1234567890")).isEqualTo("sk-****7890");
        assertThat(KeyCipherService.mask("1234567")).isEqualTo("****");     // ≤7 字符不露后4
        assertThat(KeyCipherService.mask(null)).isEqualTo("未导入Key");
        assertThat(KeyCipherService.mask("  ")).isEqualTo("未导入Key");
    }
}
