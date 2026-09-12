package com.blog.module.ai.crypto;

/**
 * API Key 解密失败专用异常（区别于部署级错误 {@link IllegalStateException}）。
 *
 * <p>为什么要单独一个类型：ai_config 种子行的密文是占位串
 * {@code __PENDING_KEY_IMPORT__}（M1 落库时不含真实 Key，设计红线）。运行时解密
 * 命中占位串是"预期内、可恢复、要引导用户去配置"的状态——它应回 4001
 * 「AI 服务未配置，请联系管理员导入 API Key」，而不是 500 或含糊的 4002。
 * 类型化异常让 AiFacade 能用 catch 精确路由错误码，而不是解析异常文案。
 */
public class KeyDecryptException extends RuntimeException {

    public KeyDecryptException(String message, Throwable cause) {
        super(message, cause);
    }
}
