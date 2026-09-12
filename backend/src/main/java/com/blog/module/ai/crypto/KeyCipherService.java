package com.blog.module.ai.crypto;

import com.blog.common.BizCode;
import com.blog.common.BizException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

/**
 * Key 加解密门面（《AI模块设计.md》§2.2）：把"主密钥从环境变量读取"这一部署约束
 * 收口在一个 Bean 里，Service/Controller 只谈业务，不碰 byte[] 与 Cipher。
 *
 * <p>主密钥配置键：{@code blog.ai.master-key}，值来自环境变量
 * {@code AI_KEY_ENC_SECRET}（见 application.yml 占位）。支持两种写法：
 * 32 字节的 hex 串（{@code openssl rand -hex 32} 的产物，64 个字符）或恰好
 * 32 字符的明文口令串——开发环境给一个默认值方便起步，注释与 README 明示
 * 生产必须替换成随机 hex（默认值本身在仓库里，等于没有密钥）。
 *
 * <p>延迟解析（dual-check 在方法里而非构造函数）：Spring 上下文在测试里也会启动
 * 全部 Bean，若在构造函数抛异常，则不关心 AI 的存量测试（M2~M5）全挂。
 * 密钥缺失只在"真的加解密时"报错，报错文案直接给出修复指令。
 */
@Service
public class KeyCipherService {

    /** 原始配置值（hex 串或 32 字符口令串），解析结果缓存到 volatile 字段 */
    private final String rawSecret;
    /** 解析后的 32 字节主密钥；null 表示尚未解析/解析失败（失败时每次调用重新抛错） */
    private volatile byte[] cachedKey;

    public KeyCipherService(@Value("${blog.ai.master-key:}") String rawSecret) {
        this.rawSecret = rawSecret;
    }

    /** 加密明文 Key → 密文信封（写库前调用；调用方随即丢弃明文） */
    public String encrypt(String plainKey) {
        return AesGcmUtil.encrypt(plainKey, keyBytes());
    }

    /** 解密信封 → 明文 Key（仅在发起真实外呼前调用；结果不进日志、不进 DTO） */
    public String decrypt(String cipherText) {
        return AesGcmUtil.decrypt(cipherText, keyBytes());
    }

    /**
     * 计算展示掩码：保留前 3 字符 + "****" + 后 4 字符（设计文档 §2.1 口径）。
     *
     * <p>为什么掩码由后端在写入时算好存列，而不是读时现算：读时算需要解密，
     * 意味着每次列表请求都触发一次 AES 运算与主密钥暴露面；掩码是不可逆的
     * 展示衍生物，冗余存储换取"列表路径永不碰密文"，也让 DBA 直查表即可
     * 核对"哪行导入了 Key"（mask='未导入Key' 即占位行）。
     */
    public static String mask(String plainKey) {
        if (plainKey == null || plainKey.isBlank()) {
            return "未导入Key";
        }
        String k = plainKey.trim();
        if (k.length() <= 7) {
            // 太短的 Key 若照常"前3+后4"会把 7 个字符全露出来；整体打星，宁可少信息不可多泄露
            return "****";
        }
        return k.substring(0, 3) + "****" + k.substring(k.length() - 4);
    }

    /**
     * 解析主密钥：hex（64 字符）优先，其余按 32 字符明文口令处理。
     * 空/非法长度 → 抛 {@link BizException}(4001)：Key 导入缺失是"未配置"业务态，
     * 不该冒 500；文案指向环境变量名，运维一看日志就知道缺什么。
     */
    private byte[] keyBytes() {
        byte[] k = cachedKey;
        if (k != null) {
            return k;
        }
        byte[] parsed = parseSecret(rawSecret);
        cachedKey = parsed;
        return parsed;
    }

    /** 静态可测：把"字符串→32字节密钥"的解析规则独立出来，AesGcmUtilTest 直接锁死边界 */
    public static byte[] parseSecret(String secret) {
        if (secret == null || secret.isBlank()) {
            throw new BizException(BizCode.AI_NOT_CONFIGURED,
                    "AI 密钥主密钥未配置：请设置环境变量 AI_KEY_ENC_SECRET（生产 openssl rand -hex 32）");
        }
        String s = secret.trim();
        // 64 位十六进制串 → 32 字节（推荐形态）。用 ASCII 正则而非 Character.digit：
        // 后者接受阿拉伯-印度数字等 Unicode 数字，HexFormat.parseHex 只认 [0-9a-fA-F]，
        // 两者口径不一致会让"看起来是 hex 的串"抛内部异常——判断即校验，一步到位
        if (s.matches("[0-9a-fA-F]{64}")) {
            return HexFormat.of().parseHex(s);
        }
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        if (bytes.length == 32) {
            return bytes;
        }
        throw new BizException(BizCode.AI_NOT_CONFIGURED,
                "AI 密钥主密钥格式非法：需 64 位 hex 或恰好 32 字符（当前 UTF-8 字节数=" + bytes.length + "）");
    }
}
