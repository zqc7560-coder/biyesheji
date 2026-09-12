package com.blog.module.ai.service;

import com.blog.module.ai.dto.AiGenerateDTO;
import com.blog.module.ai.vo.AiStatusVo;
import com.blog.module.ai.vo.SummaryResult;
import com.blog.module.ai.vo.TestResult;

/**
 * 大模型统一调用门面（《AI模块设计.md》§3.1 LlmFacade 的本轮落地子集）。
 *
 * <p>管道顺序（§3.1"每步可单测"的链条）：
 * 鉴权与开关 → 限流 → 缓存查找 → （未命中）取配置+解密Key → 装配提示词
 * → HTTP 调用（超时/重试）→ 解析校验 → 结果缓存 → 记账日志 → 返回。
 *
 * <p>M6 范围：generateSummary / testConnection / status；
 * generateTags 与 batchEnrich 的调用链共用本门面的管道骨架，随 M7 在同一实现类扩展
 * （接口先行声明语义，避免 M7 破坏 M6 契约）。
 */
public interface LlmFacade {

    /**
     * 生成文章摘要（同步语义；Web 层用 DeferredResult+aiExecutor 卸载 Tomcat 线程）。
     * 一切可预期失败都收敛为 {@link com.blog.common.BizException}（4001~4005 家族），
     * 绝不抛裸 RuntimeException 冒 500——"AI 挂不影响发文"的降级承诺从错误类型上兑现。
     */
    SummaryResult generateSummary(Long userId, AiGenerateDTO cmd);

    /** 管理员连通性测试（§2.2 步骤 3：最小成本调用，4101/4102 精确映射） */
    TestResult testConnection(Long configId, Long adminUserId);

    /** 前台按钮可用态探测（GET /api/ai/status 的数据源；userId 可空=游客只看开关） */
    AiStatusVo status(Long userId);
}
