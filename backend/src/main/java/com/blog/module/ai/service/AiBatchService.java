package com.blog.module.ai.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.blog.common.BizCode;
import com.blog.common.BizException;
import com.blog.module.article.entity.Article;
import com.blog.module.article.mapper.ArticleMapper;
import com.blog.module.article.service.ArticleService;
import com.blog.module.ai.dto.AiGenerateDTO;
import com.blog.module.ai.vo.SummaryResult;
import com.blog.module.ai.vo.TagsResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 批量补全服务（M7；《AI模块设计.md》§7.4 / API 文档 §7 POST /api/ai/batch）。
 *
 * <p><b>本类是 AI 模块唯一允许写业务表的地方</b>——设计红线：AI 结果默认"只回显、
 * 用户确认后随文章保存入库"（§5.2），因为确认权属于用户。batch 是红线的唯一豁免：
 * 管理员显式发起的治理动作本身就是确认（"给这批缺摘要/标签的文章补上"），
 * 所以它替一批文章写库。把豁免收进独立类（而不是摊在门面里），
 * 审计"AI 模块到底碰了哪些表"只需要看这一个文件。
 *
 * <p>写库不自己拼 UPDATE：摘要走 articleMapper 窄列更新 + 标签走
 * ArticleService 的公开保存链路语义——冗余计数（tag.article_count）的结算规则
 * 只许有一份事实源（M3/M4 台账反复强调的账实一致），AI 侧重新实现必漂移。
 *
 * <p>异步语义（§7.4）：@Async("aiExecutor") 提交进 AI 专用池 + 逐条 500ms
 * 间隔"手动限速"——博客体量没有真 MQ，用最小代价模拟离线队列的"不冲击上游
 * 速率限制"效果；若日后接真 MQ，只需把本类 runAsync 的投递换成发 ai-task 消息，
 * 门面与 Controller 零改动。
 */
@Slf4j
@Service
public class AiBatchService {

    /** 单批上限：批量是唯一"自动写库"入口，小步慢跑——20 篇 × 2 次调用 = 40 次真实外呼封顶 */
    static final int MAX_BATCH = 20;

    /** 逐条限速间隔（§7.4：每批间隔 500ms，防连续调用打满厂商 QPS 触发 429 连锁失败） */
    private static final long THROTTLE_MS = 500;

    private final ArticleMapper articleMapper;
    private final ArticleService articleService;
    /**
     * 回门面的延迟句柄：门面 → 本服务 → 门面 构成构造器环，Spring Boot 3
     * 默认禁止循环引用会在启动期直接失败。ObjectProvider 的注入本身不解析
     * LlmFacade Bean（首次 getObject() 才解析），从根上断环；
     * 比在字段上标 @Lazy 可靠——@RequiredArgsConstructor 生成的构造参数
     * 不带字段注解（Lombok 只拷贝 lombok.copyableAnnotations 白名单内的注解），
     * @Lazy 会静默失效（实测坑，写进台账）。
     */
    private final org.springframework.beans.factory.ObjectProvider<LlmFacade> facadeProvider;

    public AiBatchService(ArticleMapper articleMapper, ArticleService articleService,
                          org.springframework.beans.factory.ObjectProvider<LlmFacade> facadeProvider) {
        this.articleMapper = articleMapper;
        this.articleService = articleService;
        this.facadeProvider = facadeProvider;
    }

    /**
     * 候选圈定（同步，Controller 拿它做"将处理 N 篇"的回显与拒绝）。
     *
     * <p>两种入口语义（对齐"管理员治理工具"定位）：
     * ① 显式传 articleIds → 逐 id 校验存在且未删除（幽灵 id 直接 400 拒整批——
     *   管理员的提交表单出幽灵意味着页面数据过期，静默跳过会让他以为"补了"其实没补）；
     * ② 不传 → 自动圈"已发布且缺摘要或缺标签"的文章，按 id 升序取前 {@value #MAX_BATCH} 篇
     *   （升序=最老的文章先被治理，"欠账先还"）。
     */
    public List<Long> resolveCandidates(List<Long> requested) {
        if (requested != null && !requested.isEmpty()) {
            Set<Long> distinct = new LinkedHashSet<>(requested);
            if (distinct.size() > MAX_BATCH) {
                throw new BizException(BizCode.PARAM_ERROR,
                        "单批最多 " + MAX_BATCH + " 篇，请分批提交");
            }
            List<Article> found = articleMapper.selectBatchIds(distinct);
            if (found.size() != distinct.size()) {
                throw new BizException(BizCode.PARAM_ERROR, "存在不存在或已删除的文章 id，整批拒绝");
            }
            return new ArrayList<>(distinct);
        }
        // 自动圈定：只碰"已发布"文章——草稿是作者的私域，AI 替作者改草稿是越权（1005 语义都不必抛，直接不选）
        List<Article> candidates = articleMapper.selectList(new LambdaQueryWrapper<Article>()
                .select(Article::getId)
                .eq(Article::getStatus, ArticleService.STATUS_PUBLISHED)
                .and(w -> w.isNull(Article::getSummary).or().eq(Article::getSummary, "")
                        .or().apply("NOT EXISTS (SELECT 1 FROM article_tag at_ WHERE at_.article_id = article.id)"))
                .orderByAsc(Article::getId)
                .last("LIMIT " + MAX_BATCH));
        return candidates.stream().map(Article::getId).toList();
    }

    /**
     * 异步执行批量补全。<b>绝不在本方法上加 @Transactional</b>：整批一个事务意味着
     * 第 19 篇失败会回滚前 18 篇已经花过钱的成果——每篇独立提交才是"逐条失败只跳过"
     * 的正确实现（applyAiSummary/applyAiTags 各自短事务）。
     */
    @Async("aiExecutor")
    public void runAsync(List<Long> articleIds, Long adminUserId) {
        int done = 0, failed = 0;
        for (Long id : articleIds) {
            try {
                enrichOne(id, adminUserId);
                done++;
            } catch (Exception e) {
                // 单篇失败吞掉继续：AI 降级矩阵的批量版——一坏不拖全批（§3.5"AI 挂不影响主流程"）
                failed++;
                log.warn("批量补全跳过文章 {}：{}", id, e.getMessage());
            }
            try {
                Thread.sleep(THROTTLE_MS);   // 逐条限速（§7.4）；被中断则放弃剩余批次
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                log.warn("批量补全被中断，已完成 {} 篇", done);
                return;
            }
        }
        log.info("AI 批量补全完成：成功 {} 篇 / 失败 {} 篇（发起人 {}）", done, failed, adminUserId);
    }

    /**
     * 单篇补全：缺摘要则生成并窄列写回，缺标签则生成并走 ArticleService 关联重建。
     *
     * <p>幂等设计（重复提交同一批不重复花钱）：进入前重读文章，"摘要和标签都已齐"
     * 直接跳过——缓存命中虽不花钱，但一次跳过省两条日志与两次写，治理语义也更干净。
     */
    private void enrichOne(Long articleId, Long adminUserId) {
        Article article = articleMapper.selectById(articleId);
        if (article == null) {
            return;   // 排队期间被删：静默跳过（异步任务里没人接收 404 的弹框）
        }
        boolean needSummary = article.getSummary() == null || article.getSummary().isBlank();
        boolean needTags = !articleService.hasTags(articleId);
        if (!needSummary && !needTags) {
            return;
        }

        AiGenerateDTO cmd = new AiGenerateDTO();
        cmd.setTitle(article.getTitle());
        cmd.setContentMd(article.getContentMd());

        if (needSummary) {
            LlmFacade facade = facadeProvider.getObject();   // 首次真正调用时才解析（见字段注释）
            SummaryResult s = facade.generateSummary(adminUserId, cmd);
            // summary_source=1：详情页"摘要由 AI 生成"角标的数据源（§8 合规行）——
            // 批量补出来的摘要没经过人工润色，合规标识必须打，这是和编辑器回填路径同款语义
            articleService.applyAiSummary(articleId, s.getSummary());
        }
        if (needTags) {
            TagsResult t = facadeProvider.getObject().generateTags(adminUserId, cmd);
            articleService.applyAiTags(articleId, t.getTags());
        }
    }
}
