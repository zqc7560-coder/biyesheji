package com.blog.module.comment.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.blog.common.BizCode;
import com.blog.common.BizException;
import com.blog.common.PageResult;
import com.blog.module.article.entity.Article;
import com.blog.module.article.mapper.ArticleMapper;
import com.blog.module.article.service.ArticleService;
import com.blog.module.comment.dto.CommentSaveDTO;
import com.blog.module.comment.entity.Comment;
import com.blog.module.comment.mapper.CommentMapper;
import com.blog.module.comment.vo.CommentVO;
import com.blog.module.system.entity.SysConfig;
import com.blog.module.system.mapper.SysConfigMapper;
import com.blog.module.user.entity.SysUser;
import com.blog.module.user.mapper.SysUserMapper;
import com.blog.security.LoginUser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.safety.Safelist;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 评论核心服务（M5）—— 覆盖《需求分析.md》UC-U-10/11、UC-V-03、UC-A-05 与
 * 《项目计划.md》阶段五任务 1~3：两级发表、审核开关、冗余计数、删除治理。
 *
 * <p>三条贯穿全类的硬规则（与 M3 ArticleService 同构的设计决策）：
 * <ol>
 *   <li><b>两级封顶在写入侧强制</b>（§3.10"回复的回复仍挂在顶级下"）：parent 必须
 *       是顶级且属于同一篇文章，越级/跨文章一律 2302。查询侧因此只需两层装配，
 *       不需要递归 CTE——把复杂度花哨消灭在入口而不是用高级语法兜底。</li>
 *   <li><b>comment_count 只在"发布态迁移"时结算</b>（§3.6"审核通过时 +1"）：
 *       待审评论不进计数，通过/屏蔽/删除三处迁移对称 ±1，GREATEST 钳非负。
 *       计数与可见性同事务维护，否则刷新详情页会出现"角标 3 条列表 2 条"。</li>
 *   <li><b>评论正文是纯文本不是 Markdown</b>（NFR-05"评论纯文本转义"）：
 *       入库前 jsoup Safelist.none() 剥光一切标签/事件属性（评论框没有"作者懂
 *       Markdown"的默契，HTML 混写只有攻击价值没有表达价值），前端渲染用
 *       文本插值 {{content}} 而非 v-html——两道闸都不给存储型 XSS 留缝。</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CommentService {

    private final CommentMapper commentMapper;
    private final ArticleMapper articleMapper;
    private final SysUserMapper userMapper;
    private final SysConfigMapper sysConfigMapper;
    /**
     * M9：comment_count 是详情 VO 的字段，计数一变旧缓存就是脏数据。
     * 失效点收口在 bumpCommentCount（见该方法）——全部四个增减调用方
     * （直发/审核/删除/连带屏蔽）自动覆盖，新增调用方不可能漏网。
     * ArticleCacheService 是叶子组件（只依赖 Redis/ObjectMapper），构造注入无环。
     */
    private final com.blog.module.article.service.ArticleCacheService articleCache;

    /** 审核状态常量（§3.10：0 待审核 / 1 已通过 / 2 已屏蔽） */
    public static final int STATUS_PENDING = 0;
    public static final int STATUS_APPROVED = 1;
    public static final int STATUS_BLOCKED = 2;

    /** 顶级评论的 parent_id 约定值（§3.10：0=顶级，与分类表 parent_id 同风格） */
    private static final long ROOT_PARENT = 0L;

    /** 防刷屏：同一用户在同一文章下最小发言间隔（秒）。H2/真实库同规则，纯查询判定，
     *  不引入 Redis 计数器——限流器是 M7/M9 Redis 体系的活，本轮用"上一条时间戳"足够 */
    private static final int FLOOD_INTERVAL_SECONDS = 10;

    // ==================================================================
    // 发表
    // ==================================================================

    /**
     * 发表评论/回复（POST /api/comments）。
     *
     * <p>校验次序即"成本递增"次序：格式（DTO 已挡）→ 文章可评性（1 查）→
     * 防刷屏（1 查）→ 父链合法性（1 查）→ 落库。把最便宜的拒绝放前面，
     * 恶意刷请求时为后层省查询。
     *
     * @return 载荷 {comment, pending}：先审后发开启时 comment=null、pending=true
     *         （文档 §6：等待审核时不回评论对象，前端只显示提示文案）
     */
    @Transactional
    public Map<String, Object> publish(LoginUser me, CommentSaveDTO dto) {
        // 1. 发言者自身状态：JWT 无状态方案的盲区是"签发后被封号/禁用"——
        //    登录链拦不住存量令牌（需求分析规则 3"被禁言用户的评论直接拒绝"），
        //    所以在唯一的发言写入口回查 sys_user.status（1 次主键查询换语义正确，值）。
        SysUser speaker = userMapper.selectById(me.getUserId());
        if (speaker == null) {
            throw new BizException(BizCode.UNAUTHORIZED);
        }
        if (speaker.getStatus() != null && speaker.getStatus() == 0) {
            throw new BizException(BizCode.ACCOUNT_DISABLED, "账号已被禁言，无法发表评论", 403);
        }

        // 2. 文章闸门：存在且已发布（草稿对非作者不可见即不可评——沿用 M3
        //    "不可见即不存在"的防枚举口径，回 2001 而不是"未发布"）；作者/ADMIN
        //    可给自己的草稿留言（自用备注场景），allowComment=0 一律 2301。
        Article article = articleMapper.selectById(dto.getArticleId());
        if (article == null) {
            throw new BizException(BizCode.ARTICLE_NOT_FOUND, BizCode.ARTICLE_NOT_FOUND.getMessage(), 404);
        }
        boolean priv = isOwnerOrAdmin(me, article);
        if (article.getStatus() != ArticleService.STATUS_PUBLISHED && !priv) {
            throw new BizException(BizCode.ARTICLE_NOT_FOUND, BizCode.ARTICLE_NOT_FOUND.getMessage(), 404);
        }
        if (article.getAllowComment() == null || article.getAllowComment() == 0) {
            throw new BizException(BizCode.COMMENT_NOT_ALLOWED);
        }

        // 3. 正文清洗：jsoup 剥标签（含事件属性/on* 残留）→ 截断收敛到 1000 →
        //    纯空白拒绝。清洗后为空但清洗前非空，说明用户发的是一整段 HTML 载荷，
        //    回 400 明确拒绝而不是静默存空串（静默成功会让用户以为评论发出去了）。
        String content = sanitize(dto.getContent());

        // 4. 防刷屏：同用户同文章 10 秒内连发拒绝（博客量级的"防无意识双击/脚本连刷"
        //    土办法够用且零新依赖；正经限流框架随 M7 Redis 配额体系一并来）
        Long recent = commentMapper.selectCount(new LambdaQueryWrapper<Comment>()
                .eq(Comment::getArticleId, dto.getArticleId())
                .eq(Comment::getUserId, me.getUserId())
                .gt(Comment::getCreatedAt, LocalDateTime.now().minusSeconds(FLOOD_INTERVAL_SECONDS)));
        if (recent != null && recent > 0) {
            throw new BizException(BizCode.PARAM_ERROR, "发言太频繁，请稍后再试");
        }

        // 5. 父链校验（两级封顶的写入侧执法，§3.10）
        Comment parent = resolveParent(dto);

        // 6. 审核开关决定初始状态：开=0 待审（不进 comment_count），关=1 直发（计数+1）
        boolean auditOn = isAuditEnabled();
        Comment comment = new Comment();
        comment.setArticleId(dto.getArticleId());
        comment.setUserId(me.getUserId());
        comment.setParentId(parent == null ? ROOT_PARENT : parent.getId());
        // replyToUid：显式传入已在 resolveParent 校验存在；未传但回复时默认@父评论作者
        comment.setReplyToUid(dto.getReplyToUid() != null ? dto.getReplyToUid()
                : (parent == null ? null : parent.getUserId()));
        comment.setContent(content);
        comment.setStatus(auditOn ? STATUS_PENDING : STATUS_APPROVED);
        LocalDateTime now = LocalDateTime.now();
        comment.setCreatedAt(now);
        comment.setUpdatedAt(now);
        commentMapper.insert(comment);

        if (!auditOn) {
            bumpCommentCount(comment.getArticleId(), +1);
        }
        log.info("用户 {} 在文章 {} 发表{}评论 id={}", me.getUserId(), dto.getArticleId(),
                auditOn ? "待审" : "直发", comment.getId());

        Map<String, Object> data = new HashMap<>();
        data.put("pending", auditOn);
        data.put("comment", auditOn ? null : assembleOne(comment, loadUsers(List.of(comment))));
        return data;
    }

    /**
     * 父评论合法性判定（2302 三连）：存在（未删）、是顶级、与本次评论同文章。
     *
     * <p>同文章校验不可省：少了它，攻击者可以对文章 B 的评论 id 在文章 A 下"回复"，
     * 前端把 B 的正文片段渲染进 A 的评论区，等于借刀传声。层级校验则把
     * "回复的回复"折叠到顶级下（parentId 一律归一到顶级，见 publish 的 set 逻辑）。
     */
    private Comment resolveParent(CommentSaveDTO dto) {
        Long parentId = dto.getParentId();
        if (parentId == null || parentId == 0L) {
            if (dto.getReplyToUid() != null && userMapper.selectById(dto.getReplyToUid()) == null) {
                throw new BizException(BizCode.PARAM_ERROR, "被回复用户不存在");
            }
            return null;
        }
        Comment parent = commentMapper.selectById(parentId); // @TableLogic 已滤 deleted=1
        if (parent == null || parent.getParentId() != ROOT_PARENT
                || !parent.getArticleId().equals(dto.getArticleId())) {
            throw new BizException(BizCode.COMMENT_PARENT_INVALID);
        }
        if (dto.getReplyToUid() != null && userMapper.selectById(dto.getReplyToUid()) == null) {
            throw new BizException(BizCode.PARAM_ERROR, "被回复用户不存在");
        }
        return parent;
    }

    // ==================================================================
    // 查询
    // ==================================================================

    /**
     * 文章评论区（GET /api/comments?articleId=，公开）：两级嵌套，时间正序。
     *
     * <p>正序（而非列表页惯用的倒序）是产品决策：评论区是"对话"，后发言者引用
     * 先发言者，倒序会把"回复"甩到被回复内容的上面，读感断裂；文章列表才需要
     * 最新优先。
     *
     * <p>与发表同款的 2001 闸门：未发布文章的评论区对非作者不可枚举
     * （有评论本身就会泄露草稿的存在与热度）。
     */
    public PageResult<CommentVO> articleComments(Long articleId, LoginUser me) {
        Article article = articleMapper.selectById(articleId);
        if (article == null) {
            throw new BizException(BizCode.ARTICLE_NOT_FOUND, BizCode.ARTICLE_NOT_FOUND.getMessage(), 404);
        }
        if (article.getStatus() != ArticleService.STATUS_PUBLISHED && !isOwnerOrAdmin(me, article)) {
            throw new BizException(BizCode.ARTICLE_NOT_FOUND, BizCode.ARTICLE_NOT_FOUND.getMessage(), 404);
        }
        // 一次查该文章全部"已通过"评论（含两层），内存装配嵌套结构。
        // 不分页的口径：单篇文章评论量在毕设体量下 <500 条，两层结构分页会把
        // "父在页 1、子在页 2"切碎；真要上千再按顶级分页（届时只算顶级条数）。
        List<Comment> all = commentMapper.selectList(new LambdaQueryWrapper<Comment>()
                .eq(Comment::getArticleId, articleId)
                .eq(Comment::getStatus, STATUS_APPROVED)
                .orderByAsc(Comment::getCreatedAt)
                .orderByAsc(Comment::getId)); // createdAt 同秒时以自增 id 定序，翻页/刷新次序稳定
        if (all.isEmpty()) {
            return new PageResult<>(0, 1, 1, 0, List.of());
        }
        Map<Long, CommentVO.UserBrief> users = loadUsers(all);
        // 顶级按 createdAt 升序（上面 SQL 已排）；回复挂进各自 replies 桶
        Map<Long, CommentVO> tops = new LinkedHashMap<>();
        List<CommentVO> orphanReplies = new ArrayList<>(); // 防御：父被删但子还在的悬挂回复
        for (Comment c : all) {
            CommentVO vo = assembleOne(c, users);
            if (c.getParentId() == ROOT_PARENT) {
                vo.setReplies(new ArrayList<>());
                tops.put(c.getId(), vo);
            } else {
                CommentVO top = tops.get(c.getParentId());
                if (top != null) {
                    top.getReplies().add(vo);
                } else {
                    orphanReplies.add(vo); // 理论上不该出现（删除走级联），出现也不丢数据
                }
            }
        }
        List<CommentVO> list = new ArrayList<>(tops.values());
        list.addAll(orphanReplies);
        return new PageResult<>(list.size(), 1, 1, list.size(), list);
    }

    /**
     * 管理端评论分页（GET /api/admin/comments?status=&pageNum=&pageSize=，UC-A-05）：
     * 平铺（不嵌套）+ 倒序（审核队列最新优先）。
     *
     * <p>平铺是刻意的：审核以"条"为决策单位，嵌套展示会把"屏蔽这条回复"的操作
     * 埋进折叠里；待审列表里父评论可能已通过了，树形反而误导。
     */
    public PageResult<CommentVO> adminPage(Integer status, long pageNum, long pageSize) {
        Page<Comment> page = commentMapper.selectPage(new Page<>(pageNum, pageSize),
                new LambdaQueryWrapper<Comment>()
                        .eq(status != null, Comment::getStatus, status)
                        .orderByDesc(Comment::getCreatedAt)
                        .orderByDesc(Comment::getId));
        if (page.getRecords().isEmpty()) {
            return new PageResult<>(page.getTotal(), page.getPages(), page.getCurrent(), page.getSize(), List.of());
        }
        Map<Long, CommentVO.UserBrief> users = loadUsers(page.getRecords());
        Map<Long, String> titles = loadTitles(page.getRecords());
        List<CommentVO> vos = page.getRecords().stream().map(c -> {
            CommentVO vo = assembleOne(c, users);
            vo.setArticleTitle(titles.get(c.getArticleId())); // 审核员要知道"这条喷的是哪篇"
            return vo;
        }).collect(Collectors.toList());
        return new PageResult<>(page.getTotal(), page.getPages(), page.getCurrent(), page.getSize(), vos);
    }

    /**
     * 我的评论（GET /api/comments?mine=1，UC-U-12）：平铺倒序、含全部状态——
     * 被屏蔽也要让自己看到（透明治理；屏蔽理由文案本期不做，状态即信息）。
     */
    public PageResult<CommentVO> myComments(LoginUser me, long pageNum, long pageSize) {
        Page<Comment> page = commentMapper.selectPage(new Page<>(pageNum, pageSize),
                new LambdaQueryWrapper<Comment>()
                        .eq(Comment::getUserId, me.getUserId())
                        .orderByDesc(Comment::getCreatedAt)
                        .orderByDesc(Comment::getId));
        Map<Long, CommentVO.UserBrief> users = loadUsers(page.getRecords());
        Map<Long, String> titles = loadTitles(page.getRecords());
        List<CommentVO> vos = page.getRecords().stream().map(c -> {
            CommentVO vo = assembleOne(c, users);
            vo.setArticleTitle(titles.get(c.getArticleId()));
            return vo;
        }).collect(Collectors.toList());
        return new PageResult<>(page.getTotal(), page.getPages(), page.getCurrent(), page.getSize(), vos);
    }

    // ==================================================================
    // 删除 / 审核
    // ==================================================================

    /**
     * 删除评论（DELETE /api/comments/{id}，UC-U-11/UC-A-05）：本人或 ADMIN。
     *
     * <p>删顶级连删其下所有回复（级联逻辑删）：顶级是回复的"上下文"，上下文没了
     * 还留着"@某人 说：谢谢"是断头对话。comment_count 按被删的"已通过"条数一次性
     * 回退——每条删除都要对称结算，这是冗余列账实相符的唯一办法。
     */
    @Transactional
    public void delete(LoginUser me, Long id) {
        Comment comment = requireComment(id);
        if (!comment.getUserId().equals(me.getUserId()) && !isAdmin(me)) {
            // 先判存在再比属主（与 M3 同口径）：2002/403 与 2303/404 两码分开
            throw new BizException(BizCode.FORBIDDEN, "无权删除他人评论", 403);
        }
        List<Comment> victims = new ArrayList<>();
        victims.add(comment);
        if (comment.getParentId() == ROOT_PARENT) {
            victims.addAll(commentMapper.selectList(new LambdaQueryWrapper<Comment>()
                    .eq(Comment::getParentId, comment.getId())));
        }
        // 逐行逻辑删（@TableLogic → UPDATE deleted=1）；量小（顶级+其回复），
        // 不写 DELETE ... WHERE 注解 SQL（M4 坑#1：BlockAttack 插件解析多行注解 SQL 会炸）
        int approvedRemoved = 0;
        for (Comment c : victims) {
            commentMapper.deleteById(c.getId());
            if (c.getStatus() == STATUS_APPROVED) {
                approvedRemoved++;
            }
        }
        if (approvedRemoved > 0) {
            bumpCommentCount(comment.getArticleId(), -approvedRemoved);
        }
        log.info("用户 {} 删除评论 {}（含级联 {} 条）", me.getUserId(), id, victims.size());
    }

    /**
     * 审核（PATCH /api/admin/comments/{id}/audit，UC-A-05）：通过/屏蔽。
     *
     * <p>计数只在"状态跨界"时动：0→1 +1、1→2 -1、2→1 +1（错杀翻案）、同态幂等
     * 直接返回——重复点"通过"不该把计数刷成天文数字（API 文档 §8.4 幂等约定）。
     */
    @Transactional
    public String audit(Long id, Integer target) {
        Comment comment = requireComment(id);
        if (comment.getStatus().equals(target)) {
            return target == STATUS_APPROVED ? "评论已是通过状态" : "评论已是屏蔽状态";
        }
        Comment patch = new Comment();
        patch.setId(id);
        patch.setStatus(target);
        patch.setUpdatedAt(LocalDateTime.now());
        commentMapper.updateById(patch);
        // 屏蔽顶级连带屏蔽其回复：恢复删除侧同款"上下文一致性"——顶级被屏蔽后
        // 若回复仍可见，会出现"引用一条不可见评论"的断头对话（回复可能还是通过的）。
        if (target == STATUS_BLOCKED && comment.getParentId() == ROOT_PARENT) {
            List<Comment> replies = commentMapper.selectList(new LambdaQueryWrapper<Comment>()
                    .eq(Comment::getParentId, id)
                    .eq(Comment::getStatus, STATUS_APPROVED));
            int n = 0;
            for (Comment r : replies) {
                Comment p = new Comment();
                p.setId(r.getId());
                p.setStatus(STATUS_BLOCKED);
                p.setUpdatedAt(LocalDateTime.now());
                commentMapper.updateById(p);
                n++;
            }
            if (comment.getStatus() == STATUS_APPROVED) {
                bumpCommentCount(comment.getArticleId(), -(1 + n));
            } else {
                bumpCommentCount(comment.getArticleId(), -n);
            }
        } else if (comment.getStatus() == STATUS_APPROVED && target == STATUS_BLOCKED) {
            bumpCommentCount(comment.getArticleId(), -1);
        } else if (comment.getStatus() != STATUS_APPROVED && target == STATUS_APPROVED) {
            bumpCommentCount(comment.getArticleId(), +1);
        }
        return target == STATUS_APPROVED ? "已通过" : "已屏蔽";
    }

    // ==================================================================
    // 内部：闸门 / 清洗 / 装配
    // ==================================================================

    /** 按 id 取评论（逻辑删已过滤），不存在 → 2303/404（与 2202 标签幽灵同族语义） */
    private Comment requireComment(Long id) {
        Comment comment = commentMapper.selectById(id);
        if (comment == null) {
            throw new BizException(BizCode.COMMENT_NOT_FOUND, BizCode.COMMENT_NOT_FOUND.getMessage(), 404);
        }
        return comment;
    }

    /**
     * 正文清洗：jsoup Safelist.none() 把一切标签连事件属性剥光（与 M3
     * MarkdownProcessor 同一把刀，但评论没有 Markdown 语法要保留，一步到位），
     * 再折行收敛为单段文本——VARCHAR(1000) 是字符数口径，多行换行符也占列宽。
     */
    private String sanitize(String raw) {
        String cleaned = Jsoup.clean(raw, Safelist.none());           // 剥 HTML
        cleaned = cleaned.replace("\r", "").replace("\n", " ");        // 换行折叠成空格
        cleaned = cleaned.replaceAll("\\s+", " ").trim();              // 连续空白收敛
        if (cleaned.isEmpty()) {
            throw new BizException(BizCode.PARAM_ERROR, "评论内容不能为空白或纯标签");
        }
        if (cleaned.length() > 1000) {
            cleaned = cleaned.substring(0, 1000); // DTO 已限 1000，此处是清洗规则变化的双保险
        }
        return cleaned;
    }

    /** 审核开关：sys_config.comment_audit=true 时先审后发；缺省/异常值视为关（与种子 false 一致） */
    private boolean isAuditEnabled() {
        SysConfig config = sysConfigMapper.selectOne(new LambdaQueryWrapper<SysConfig>()
                .eq(SysConfig::getConfigKey, "comment_audit"));
        return config != null && "true".equalsIgnoreCase(config.getConfigValue());
    }

    /** comment_count 增减：UPDATE 表达式列原子结算（M3 同款——读-改-写并发会互相吞计数） */
    private void bumpCommentCount(Long articleId, int delta) {
        articleMapper.update(null, new LambdaUpdateWrapper<Article>()
                .eq(Article::getId, articleId)
                .setSql("comment_count = GREATEST(comment_count + (" + delta + "), 0)"));
        // M9：计数变更 → 详情缓存脏（comment_count 在 VO 里）。evictTx 的双删结构
        // 与 ArticleService 写路径同一套纪律；delta=0 的幂等分支在上游已提前 return，
        // 走到这里必然是"账真的动了"，无脑失效即可（漏删的代价是 30min 脏读，
        // 多删的代价是一次回源——两边不对称，宁可多删）。
        articleCache.evictTx(articleId);
    }

    private boolean isOwnerOrAdmin(LoginUser me, Article article) {
        return me != null && (article.getUserId().equals(me.getUserId()) || isAdmin(me));
    }

    private boolean isAdmin(LoginUser me) {
        return me != null && me.getRoles() != null && me.getRoles().contains("ROLE_ADMIN");
    }

    /** 批量取评论涉及的"人"（发表者 ∪ 被@者），一次 IN 查询防 N+1（M3 装配惯例） */
    private Map<Long, CommentVO.UserBrief> loadUsers(List<Comment> comments) {
        Set<Long> ids = comments.stream()
                .flatMap(c -> java.util.stream.Stream.of(c.getUserId(), c.getReplyToUid()))
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());
        if (ids.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<Long, CommentVO.UserBrief> map = new HashMap<>();
        for (SysUser u : userMapper.selectBatchIds(ids)) {
            map.put(u.getId(), CommentVO.UserBrief.builder()
                    .id(u.getId()).nickname(u.getNickname()).avatar(u.getAvatar()).build());
        }
        return map;
    }

    /** 批量取评论涉及的文章标题（审核列表/我的评论跳转用） */
    private Map<Long, String> loadTitles(List<Comment> comments) {
        Set<Long> ids = comments.stream().map(Comment::getArticleId).collect(Collectors.toSet());
        Map<Long, String> map = new HashMap<>();
        for (Article a : articleMapper.selectBatchIds(ids)) {
            map.put(a.getId(), a.getTitle());
        }
        return map;
    }

    /** 实体 → VO（单条装配；replies 由调用方按场景填充） */
    private CommentVO assembleOne(Comment c, Map<Long, CommentVO.UserBrief> users) {
        return CommentVO.builder()
                .id(c.getId())
                .articleId(c.getArticleId())
                .user(users.get(c.getUserId()))
                .replyTo(c.getReplyToUid() == null ? null : users.get(c.getReplyToUid()))
                .content(c.getContent())
                .status(c.getStatus())
                .createdAt(c.getCreatedAt())
                .build();
    }
}
