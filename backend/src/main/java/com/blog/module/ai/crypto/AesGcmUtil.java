package com.blog.module.ai.crypto;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * AES-256-GCM 加解密工具（《AI模块设计.md》§2.2 / 项目计划难点 D-2）。
 *
 * <p>密文封装格式：{@code Base64(IV[12B] || ciphertext || tag[16B])}——GCM 的认证
 * 标签由 JDK Cipher 追加在密文尾部，解密时一并与 IV 分离。选 GCM 而非 CBC 的理由：
 * CBC 无完整性校验，密文被篡改（拖库者改一字节）会静默解出垃圾；GCM 自带认证，
 * 篡改直接抛 AEADBadTagException——对"API Key"这种一旦解错就可能拿错凭据去外呼的
 * 场景，完整性是硬需求。
 *
 * <p>IV 策略：每次加密用 {@link SecureRandom} 现取 12 字节随机 IV（GCM 推荐长度，
 * NIST SP 800-38D 对单一密钥的随机 IV 上限 2^32 次调用，本场景远及不到）。
 * 同文明文两次加密得到不同密文 → 即使拖库也无法通过"密文相等"推断两台配置复用
 * 同一把 Key（设计文档 §2.2 明示的防御目标）。IV 不秘密，随密文一起存。
 *
 * <p>主密钥 32 字节（AES-256），来源是环境变量/启动参数（{@code AI_KEY_ENC_SECRET}），
 * 绝不在本类里出现任何默认密钥值——忘了配就启动失败（KeyCipherService 显式抛错），
 * 宁可拒绝服务也不能"用硬编码密钥加密"制造假安全。
 *
 * <p>本类无状态、不注册为 Bean：纯算法函数便于单测直接 new 场景（AesGcmUtilTest），
 * 密钥注入与业务语义在 KeyCipherService。
 */
public final class AesGcmUtil {

    /** GCM 推荐 IV 长度：12 字节（96bit）。换长度会显著增加 GCM 内部运算并弱化安全性 */
    private static final int IV_LENGTH_BYTES = 12;
    /** GCM 认证标签长度：128bit（JDK 参数名俗称"tagBits"） */
    private static final int TAG_LENGTH_BITS = 128;
    /** 算法变换串：AES + GCM 模式 + NoPadding（GCM 是流式模式，分块由计数器模式完成，无需填充） */
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    /**
     * 加密：随机 IV，产出 Base64(IV || ciphertext || tag)。
     *
     * @param plainKey 明文 API Key（调用方负责用完即弃——不落日志、不进 DTO）
     * @param masterKey 32 字节主密钥
     */
    public static String encrypt(String plainKey, byte[] masterKey) {
        requireKey(masterKey);
        try {
            byte[] iv = new byte[IV_LENGTH_BYTES];
            SECURE_RANDOM.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(masterKey, "AES"),
                    new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            byte[] cipherTextWithTag = cipher.doFinal(plainKey.getBytes(StandardCharsets.UTF_8));

            // 手工拼接 IV || CT||Tag：JDK 不会替你把 IV 放进密文，解密方必须能取回 IV
            byte[] envelope = new byte[iv.length + cipherTextWithTag.length];
            System.arraycopy(iv, 0, envelope, 0, iv.length);
            System.arraycopy(cipherTextWithTag, 0, envelope, iv.length, cipherTextWithTag.length);
            return Base64.getEncoder().encodeToString(envelope);
        } catch (GeneralSecurityException e) {
            // 对外包装成 ISE：主密钥/算法环境问题属于部署故障，不是用户输入问题
            throw new IllegalStateException("AES-GCM 加密失败: " + e.getMessage(), e);
        }
    }

    /**
     * 解密 encrypt() 产出的信封。
     *
     * <p>Base64 非法 / 密文被篡改（tag 校验失败）都会走到这里抛
     * {@link GeneralSecurityException}，包装为带明确语义的
     * {@link KeyDecryptException}，让上层能区分"占位串没导入 Key"（4001 引导文案）
     * 与"真调用失败"（4002 降级文案）。
     */
    public static String decrypt(String base64Envelope, byte[] masterKey) {
        requireKey(masterKey);
        byte[] envelope;
        try {
            envelope = Base64.getDecoder().decode(base64Envelope);
        } catch (IllegalArgumentException e) {
            // 种子数据里 api_key_cipher 是占位串（非 Base64），首次命中此分支=提醒管理员导入 Key
            throw new KeyDecryptException("密文不是合法 Base64（疑似未导入真实 API Key）", e);
        }
        if (envelope.length <= IV_LENGTH_BYTES) {
            throw new KeyDecryptException("密文长度不足，缺少 IV/Tag", null);
        }
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(masterKey, "AES"),
                    new GCMParameterSpec(TAG_LENGTH_BITS, envelope, 0, IV_LENGTH_BYTES));
            byte[] plain = cipher.doFinal(envelope, IV_LENGTH_BYTES, envelope.length - IV_LENGTH_BYTES);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            // tag 校验失败最常见成因：主密钥换了/密文被人动过——两种都绝不能把脏 Key 发往外部 API
            throw new KeyDecryptException("AES-GCM 解密失败（主密钥不匹配或密文被篡改）", e);
        }
    }

    /** 主密钥规格检查：AES-256 必须恰好 32 字节；短密钥（如开发误配 8 位口令）直接拒绝 */
    private static void requireKey(byte[] masterKey) {
        if (masterKey == null || masterKey.length != 32) {
            throw new IllegalStateException(
                    "AI 主密钥必须为 32 字节（AES-256）。请设置环境变量 AI_KEY_ENC_SECRET"
                            + "（生产用 openssl rand -hex 32 生成），当前长度="
                            + (masterKey == null ? "null" : masterKey.length));
        }
    }

    private AesGcmUtil() {
    }
}
