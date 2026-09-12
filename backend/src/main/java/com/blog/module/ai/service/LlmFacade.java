package com.blog.module.ai.service;

import com.blog.module.ai.dto.AiGenerateDTO;
import com.blog.module.ai.vo.AiStatusVo;
import com.blog.module.ai.vo.SummaryResult;
import com.blog.module.ai.vo.TagsResult;
import com.blog.module.ai.vo.TestResult;

import java.util.List;

/**
 * 大模型统一调用门面（《AI模块设计.md》§3.1 LlmFacade 的落地形态）。
 *
 * <p>管道顺序（§3.1"每步可单测"的链条）：
 * 鉴权与开关 → 限流 → 缓存查找 → （未命中）取配置+解密Key → 装配提示词
 * → HTTP 调用（超时/重试）→ 解析校验 → 结果缓存 → 记账日志 → 返回。
 *
 * <p>M6 落地 generateSummary / testConnection / status；M7 补齐 generateTags 与
 * batchEnrich——同一条管道骨架复用（callWithDecryptedKey/缓存键/配额/记账全部共用），
 * 只有"解析与合法化"一步分叉（摘要走清洗+机械兜底，标签走三级防御+4005）。
 */
public interface LlmFacade {

    /**
     * 生成文章摘要（同步语义；Web 层用 DeferredResult+aiExecutor 卸载 Tomcat 线程）。
     * 一切可预期失败都收敛为 {@link com.blog.common.BizException}（4001~4005 家族），
     * 绝不抛裸 RuntimeException 冒 500——"AI 挂不影响发文"的降级承诺从错误类型上兑现。
     */
    SummaryResult generateSummary(Long userId, AiGenerateDTO cmd);

    /**
     * 生成文章标签（M7；语义与失败收敛与摘要一致）。
     * 解析全失败 → 4005（标签侧无机械兜底：截词当标签会污染标签表，见 ResultParserService 注释）。
     * 出参只回"建议"，入库由用户在编辑器勾选后随文章保存完成（§5.2 入库时机红线）。
     */
    TagsResult generateTags(Long userId, AiGenerateDTO cmd);

    /** 管理员连通性测试（§2.2 步骤 3：最小成本调用，4101/4102 精确映射） */
    TestResult testConnection(Long configId, Long adminUserId);

    /** 前台按钮可用态探测（GET /api/ai/status 的数据源；userId 可空=游客只看开关） */
    AiStatusVo status(Long userId);

    /**
     * 批量补全候选解析（同步）：给定 articleIds 时校验取交集；传 null/空则自动圈定
     * "已发布且缺摘要或缺标签"的文章（上限 20 篇/次——批量是唯一会"替用户写库"的
     * AI 入口，必须小步慢跑，成本与影响面双重可控）。
     */
    List<Long> resolveBatchCandidates(List<Long> requestedArticleIds);

    /**
     * 批量补全执行（异步；§7.4：@Async("aiExecutor") + 逐条 500ms 限速模拟离线队列）。
     * 逐条独立事务语义：单篇失败只跳过该篇并留痕，绝不中断整批。
     * 计费用 adminUserId 记账——审计口径"谁发起了这次批量"。
     */
    void batchEnrich(List<Long> articleIds, Long adminUserId);
}
