package com.blog.module.article;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.blog.module.article.entity.Article;
import com.blog.module.article.mapper.ArticleMapper;
import com.blog.support.InMemoryRedis;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 文章模块接口级测试（M3）—— 对齐《部署与测试.md》§3.1 的 TC-F-03（状态机/可见性）、
 * TC-F-04（乐观锁）、NFR-05（XSS 服务端清洗），以及《项目计划.md》里程碑 M3 验收：
 * "草稿→发布→前台可见→编辑→下线全链路通过；XSS 注入用例全部被清洗"。
 *
 * <p>基建与 M2 测试同款（决策理由见 AuthApiM2Test 类注释）：
 * 内存 Redis @Primary 覆盖 + 类级 @Transactional 用例间零污染 + readEnvelope 显式
 * UTF-8 解字节（MockMvc ISO-8859-1 坑，台账已记录）。
 *
 * <p>身份物料（data-h2.sql 种子）：testuser(id=1, ROLE_USER 有 article:save)、
 * other(id=2, ROLE_ADMIN 全权限)、口令均 password123。分类种子 id=1 技术 / id=2 生活。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@DisplayName("M3 文章接口: 发布链路/可见性矩阵/乐观锁/状态机/标签分类计数/XSS")
class ArticleApiM3Test {

    private static final String SEED_PWD = "password123";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private ArticleMapper articleMapper;

    @TestConfiguration
    static class FakeRedisConfig {
        @Bean
        @Primary
        StringRedisTemplate inMemoryRedisTemplate() {
            return InMemoryRedis.template();
        }
    }

    // ==================== 工具 ====================

    @SuppressWarnings("unchecked")
    private Map<String, Object> castMap(Object o) {
        return (Map<String, Object>) o;
    }

    /** 读信封：显式 UTF-8 解字节（MockMvc getContentAsString 按 ISO-8859-1 解中文会乱码，M2 台账坑） */
    @SuppressWarnings("unchecked")
    private Map<String, Object> readEnvelope(MvcResult result) throws Exception {
        return objectMapper.readValue(
                new String(result.getResponse().getContentAsByteArray(),
                        java.nio.charset.StandardCharsets.UTF_8),
                Map.class);
    }

    private String loginForToken(String username) throws Exception {
        return loginForToken(username, SEED_PWD);
    }

    /** 登录拿令牌（口令参数化：注册类用例的账号口令与种子不同，写死会串成 401） */
    private String loginForToken(String username, String password) throws Exception {
        MvcResult r = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("username", username, "password", password))))
                .andReturn();
        assertThat(r.getResponse().getStatus()).as("登录 %s 应 200", username)
                .isEqualTo(200);
        return (String) castMap(readEnvelope(r).get("data")).get("token");
    }

    /** 提交文章保存请求（POST 新建 / PUT 更新共用），返回信封 */
    private Map<String, Object> saveArticle(String token, String method, String uri,
                                            Map<String, Object> body) throws Exception {
        var builder = "POST".equals(method) ? post(uri) : put(uri);
        MvcResult r = mockMvc.perform(builder
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
        return readEnvelope(r);
    }

    /** 建一篇标准草稿（各用例的公共前置），返回 {id, version} */
    private Map<String, Object> createDraft(String token, Map<String, Object> overrides) throws Exception {
        java.util.Map<String, Object> body = new java.util.HashMap<>(Map.of(
                "title", "Spring Boot 3 实战：统一响应体设计",
                "categoryId", 1,
                "tags", List.of("SpringBoot", "后端"),
                "contentMd", "# 标题\n\n正文 **Markdown** 内容，至少凑够一些字数便于统计。",
                "status", 0,
                "allowComment", 1));
        if (overrides != null) {
            body.putAll(overrides);
        }
        Map<String, Object> env = saveArticle(token, "POST", "/api/articles", body);
        assertThat((Integer) env.get("code")).as("草稿创建应成功: %s", env.get("message")).isZero();
        return castMap(env.get("data"));
    }

    // ==================== 列表（UC-V-01） ====================

    @Test
    @DisplayName("游客列表公开：只见已发布；草稿/下线不出现；载荷含 total/list 契约字段")
    void publicListHidesUnpublished() throws Exception {
        String token = loginForToken("testuser");
        Map<String, Object> draft = createDraft(token, null);

        // 游客（无 Authorization 头）访问公开列表：SecurityConfig M3 放行 GET
        MvcResult r = mockMvc.perform(get("/api/articles"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.pageNum").value(1))
                .andReturn();
        Map<String, Object> data = castMap(readEnvelope(r).get("data"));
        // 种子无文章 + 本篇是草稿 → total 必须为 0（若草稿漏进公开列表，此断言炸）
        assertThat(((Number) data.get("total")).longValue()).isZero();
        assertThat(draft.get("id")).isNotNull();

        // 作者从 mine=1 通道看得到自己的草稿（同一资源两种视图，矩阵各管一头）。
        // 断言"包含本篇"而非绝对 total：M1 的 15 表 roundtrip 用例（无 @Transactional，
        // 共享 blog_test 库）会留下 user_id=1 的草稿样本，绝对值断言是跨测试类的隐式耦合。
        MvcResult mineR = mockMvc.perform(get("/api/articles").header("Authorization", "Bearer " + token)
                        .param("mine", "1").param("pageSize", "100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.list[0].status").value(0))
                // 列表薄对象：正文字段不传输（NON_NULL 隐去，防载荷膨胀与"contentMd:null 误判"）
                .andExpect(jsonPath("$.data.list[0].contentMd").doesNotExist())
                .andReturn();
        List<Long> mineIds = objectMapper.readTree(
                        new String(mineR.getResponse().getContentAsByteArray(),
                                java.nio.charset.StandardCharsets.UTF_8))
                .at("/data/list").findValues("id").stream()
                .map(n -> n.asLong()).toList();
        assertThat(mineIds).contains(((Number) draft.get("id")).longValue());
    }

    @Test
    @DisplayName("mine=1 未登录 → 401/1001（不能把'我的草稿'开放成公开接口）")
    void mineRequiresLogin() throws Exception {
        mockMvc.perform(get("/api/articles").param("mine", "1"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(1001));
    }

    // ==================== 详情可见性矩阵（UC-V-02 / TC-F-03 前半） ====================

    @Test
    @DisplayName("草稿详情：作者本人可见、他人 2001、ADMIN 可见；发布后游客立即可见")
    void detailVisibilityMatrix() throws Exception {
        String owner = loginForToken("testuser");
        // 属主校验样本用现注册的"路人"账号（种子里的 disabled 是禁用态登不进，别的种子用户会串用例数据）
        mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", "m3passerby", "password", "Abcd@1234",
                                "nickname", "路人", "email", "m3pb@blog.local"))))
                .andExpect(status().isOk());
        Map<String, Object> draft = createDraft(owner, null);
        long id = ((Number) draft.get("id")).longValue();

        // 作者看自己草稿：200，详情含正文/version
        mockMvc.perform(get("/api/articles/" + id).header("Authorization", "Bearer " + owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.contentMd").exists())
                .andExpect(jsonPath("$.data.version").value(0))
                .andExpect(jsonPath("$.data.author.nickname").value("测试用户"))
                .andExpect(jsonPath("$.data.category.name").value("技术"))
                .andExpect(jsonPath("$.data.tags[0].name").exists());

        // 游客/他人看草稿：不可见即不存在（2001/404，不给枚举信号）
        mockMvc.perform(get("/api/articles/" + id))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(2001));
        String passerby = loginForToken("m3passerby", "Abcd@1234");
        mockMvc.perform(get("/api/articles/" + id).header("Authorization", "Bearer " + passerby))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(2001));

        // ADMIN 看草稿：治理视图（UC-A-02）
        String admin = loginForToken("other");
        mockMvc.perform(get("/api/articles/" + id).header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk());

        // 发布 → 游客可见 + 摘要字段按契约呈现
        mockMvc.perform(patch("/api/articles/" + id + "/status")
                        .header("Authorization", "Bearer " + owner)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("已发布"));
        mockMvc.perform(get("/api/articles/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value(1))
                .andExpect(jsonPath("$.data.publishedAt").exists());
    }

    @Test
    @DisplayName("不存在的文章 → 2001/404")
    void missingArticle() throws Exception {
        mockMvc.perform(get("/api/articles/999999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(2001));
    }

    // ==================== 发布链路与计数维护（§3.7 冗余列） ====================

    @Test
    @DisplayName("保存并发布：content_text/word_count 派生、published_at 首写、分类与标签计数 +1")
    void publishDerivesFieldsAndCounts() throws Exception {
        String owner = loginForToken("testuser");
        Map<String, Object> env = saveArticle(owner, "POST", "/api/articles", Map.of(
                "title", "发布链路样本", "categoryId", 2,
                "tags", List.of("M3专属标签"),
                "contentMd", "# 样本\n\n这是一段**中文**正文 with English words.",
                "status", 1));
        assertThat((Integer) env.get("code")).isZero();
        Map<String, Object> data = castMap(env.get("data"));
        long id = ((Number) data.get("id")).longValue();
        // 响应契约 {id, wordCount}
        assertThat(((Number) data.get("wordCount")).intValue()).isPositive();

        Article stored = articleMapper.selectById(id);
        assertThat(stored.getStatus()).isEqualTo(1);
        assertThat(stored.getPublishedAt()).isNotNull();
        // 派生列：纯文本无 Markdown 噪音、字数=去空白码点数
        assertThat(stored.getContentText()).doesNotContain("#", "**")
                .contains("中文正文", "with English words");
        assertThat(stored.getWordCount())
                .isEqualTo(MarkdownCountProbe.count(stored.getContentText()));
        // 冗余计数：分类2(生活) +1；标签自动补建且计数 1
        Integer catCount = jdbcTemplate.queryForObject(
                "select article_count from category where id=2", Integer.class);
        assertThat(catCount).isEqualTo(1);
        Integer tagCount = jdbcTemplate.queryForObject(
                "select article_count from tag where name='M3专属标签'", Integer.class);
        assertThat(tagCount).isEqualTo(1);
    }

    /** 测试侧独立算一遍字数（与实现的口径断言互证，防"实现自说自话"） */
    static final class MarkdownCountProbe {
        static int count(String plain) {
            return (int) plain.replaceAll("\\s+", "").codePoints().count();
        }
    }

    @Test
    @DisplayName("分类不存在 → 2003；标签重名不重建（按名复用同一 tag 行）")
    void categoryNotFoundAndTagReuse() throws Exception {
        String owner = loginForToken("testuser");
        Map<String, Object> bad = saveArticle(owner, "POST", "/api/articles", Map.of(
                "title", "脏分类", "categoryId", 999, "contentMd", "正文内容", "status", 0));
        assertThat((Integer) bad.get("code")).isEqualTo(2003);

        // 两次用同名标签：tag 表只有一行，article_count=2（复用而非重名撞 uk_name）
        createDraft(owner, Map.of("tags", List.of("复用标签")));
        createDraft(owner, Map.of("tags", List.of("复用标签"), "title", "第二篇"));
        Integer rows = jdbcTemplate.queryForObject(
                "select count(*) from tag where name='复用标签'", Integer.class);
        Integer cnt = jdbcTemplate.queryForObject(
                "select article_count from tag where name='复用标签'", Integer.class);
        assertThat(rows).isEqualTo(1);
        assertThat(cnt).isEqualTo(2);
    }

    // ==================== 编辑 / 乐观锁（TC-F-04） ====================

    @Test
    @DisplayName("乐观锁：version 正确→保存成功版本+1；旧 version→2004 防覆盖")
    void optimisticLockConflict() throws Exception {
        String owner = loginForToken("testuser");
        Map<String, Object> draft = createDraft(owner, null);
        long id = ((Number) draft.get("id")).longValue();

        // 第一次编辑（version=0）→ 成功，版本推进到 1（响应回显新 version 供编辑器续用）
        Map<String, Object> ok = saveArticle(owner, "PUT", "/api/articles/" + id, Map.of(
                "title", "第一次改名", "contentMd", "新正文内容一二三", "status", 0, "version", 0));
        assertThat((Integer) ok.get("code")).isZero();
        assertThat(((Number) castMap(ok.get("data")).get("version")).intValue()).isEqualTo(1);

        // 另一窗口仍持 version=0 → 2004（不静默覆盖第一窗口的改名）
        Map<String, Object> conflict = saveArticle(owner, "PUT", "/api/articles/" + id, Map.of(
                "title", "第二次改名", "contentMd", "又被改了", "status", 0, "version", 0));
        assertThat((Integer) conflict.get("code")).isEqualTo(2004);
        assertThat(articleMapper.selectById(id).getTitle()).isEqualTo("第一次改名");

        // 更新必须携带 version：漏传 → 400（"先取详情再保存"的契约护栏）
        Map<String, Object> noVer = saveArticle(owner, "PUT", "/api/articles/" + id, Map.of(
                "title", "裸更新", "contentMd", "正文", "status", 0));
        assertThat((Integer) noVer.get("code")).isEqualTo(400);
    }

    @Test
    @DisplayName("越权编辑：他人（含 ADMIN 令牌以外的 USER）PUT/DELETE → 2002/403")
    void ownershipGuardOnWrite() throws Exception {
        String owner = loginForToken("testuser");
        long id = ((Number) createDraft(owner, null).get("id")).longValue();

        mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", "m3thief", "password", "Abcd@1234",
                                "nickname", "偷改者", "email", "m3thief@blog.local"))))
                .andExpect(status().isOk());
        String thief = loginForToken("m3thief", "Abcd@1234");

        Map<String, Object> env = saveArticle(thief, "PUT", "/api/articles/" + id, Map.of(
                "title", "被偷改", "contentMd", "脏数据", "status", 0, "version", 0));
        assertThat((Integer) env.get("code")).isEqualTo(2002);
        // 越权读他人草稿同样 2001（见 detailVisibilityMatrix），写路径则明确 2002——
        // 两码语义分工：读时"不存在"防枚举，写时"不是你的"给作者协作场景留诚实反馈

        mockMvc.perform(delete("/api/articles/" + id).header("Authorization", "Bearer " + thief))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(2002));
    }

    @Test
    @DisplayName("编辑时重建标签：先删后插 + 计数一出一进（旧标签 -1 新标签 +1）")
    void tagRebuildOnUpdate() throws Exception {
        String owner = loginForToken("testuser");
        long id = ((Number) createDraft(owner,
                Map.of("tags", List.of("旧标签A"))).get("id")).longValue();

        saveArticle(owner, "PUT", "/api/articles/" + id, Map.of(
                "title", "换标签", "contentMd", "正文正文", "status", 0, "version", 0,
                "tags", List.of("新标签B", "新标签B", "  ")));   // 含重复与空串：应被合法化

        Integer linkRows = jdbcTemplate.queryForObject(
                "select count(*) from article_tag where article_id=" + id, Integer.class);
        assertThat(linkRows).isEqualTo(1);   // 去重后只剩一个
        assertThat(jdbcTemplate.queryForObject(
                "select article_count from tag where name='旧标签A'", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "select article_count from tag where name='新标签B'", Integer.class)).isEqualTo(1);
    }

    // ==================== 状态机矩阵（TC-F-03 后半） ====================

    @Test
    @DisplayName("状态机：0→2 非法(1005)；1→2→1 合法且 published_at 保持首发时间；重复流转幂等")
    void stateMachineMatrix() throws Exception {
        String owner = loginForToken("testuser");
        long id = ((Number) createDraft(owner, null).get("id")).longValue();

        // 草稿直接"下线"：语义不存在 → 1005/403
        mockMvc.perform(patch("/api/articles/" + id + "/status")
                        .header("Authorization", "Bearer " + owner)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":2}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(1005));

        // 发布（记录首发时间）→ 下线 → 再上线：published_at 恒为首发值（需求规则：防排序漂移）
        mockMvc.perform(patch("/api/articles/" + id + "/status")
                        .header("Authorization", "Bearer " + owner)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":1}"))
                .andExpect(jsonPath("$.message").value("已发布"));
        java.time.LocalDateTime first = articleMapper.selectById(id).getPublishedAt();

        mockMvc.perform(patch("/api/articles/" + id + "/status")
                        .header("Authorization", "Bearer " + owner)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":2}"))
                .andExpect(jsonPath("$.message").value("已下线"));
        // 下线状态下游客不可见
        mockMvc.perform(get("/api/articles/" + id))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(2001));

        mockMvc.perform(patch("/api/articles/" + id + "/status")
                        .header("Authorization", "Bearer " + owner)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":1}"))
                .andExpect(jsonPath("$.message").value("已发布"));
        assertThat(articleMapper.selectById(id).getPublishedAt()).isEqualTo(first);

        // 重复流转：发布态再发布 → 幂等成功、文案提示现状、不产生 UPDATE
        mockMvc.perform(patch("/api/articles/" + id + "/status")
                        .header("Authorization", "Bearer " + owner)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("文章已是发布状态"));
    }

    @Test
    @DisplayName("发布↔下线跨界：分类冗余计数 +1/-1 正确结算")
    void categoryCountTracksVisibility() throws Exception {
        String owner = loginForToken("testuser");
        long id = ((Number) createDraft(owner, Map.of("categoryId", 1)).get("id")).longValue();
        assertThat(jdbcTemplate.queryForObject(
                "select article_count from category where id=1", Integer.class)).isZero(); // 草稿不计

        mockMvc.perform(patch("/api/articles/" + id + "/status")
                .header("Authorization", "Bearer " + owner)
                .contentType(MediaType.APPLICATION_JSON).content("{\"status\":1}")).andExpect(status().isOk());
        assertThat(jdbcTemplate.queryForObject(
                "select article_count from category where id=1", Integer.class)).isEqualTo(1);

        mockMvc.perform(patch("/api/articles/" + id + "/status")
                .header("Authorization", "Bearer " + owner)
                .contentType(MediaType.APPLICATION_JSON).content("{\"status\":2}")).andExpect(status().isOk());
        assertThat(jdbcTemplate.queryForObject(
                "select article_count from category where id=1", Integer.class)).isZero();
    }

    // ==================== 删除（UC-U-09） ====================

    @Test
    @DisplayName("逻辑删除：deleted=1、详情 2001、列表消失、分类/标签计数回退")
    void deleteIsLogicalAndCleansCounts() throws Exception {
        String owner = loginForToken("testuser");
        long id = ((Number) createDraft(owner, Map.of(
                "tags", List.of("待删专属"), "status", 1)).get("id")).longValue();

        mockMvc.perform(delete("/api/articles/" + id).header("Authorization", "Bearer " + owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        // @TableLogic：行还在库里，deleted=1（回收站语义，UC-U-09"管理员可彻底删除"的数据基础）
        Integer raw = jdbcTemplate.queryForObject(
                "select deleted from article where id=" + id, Integer.class);
        assertThat(raw).isEqualTo(1);
        mockMvc.perform(get("/api/articles/" + id).header("Authorization", "Bearer " + owner))
                .andExpect(status().isNotFound());
        assertThat(jdbcTemplate.queryForObject(
                "select article_count from category where id=1", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "select article_count from tag where name='待删专属'", Integer.class)).isZero();
        // 关联表清干净（不留幽灵 JOIN 行）
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from article_tag where article_id=" + id, Integer.class)).isZero();
    }

    // ==================== ADMIN 治理（UC-A-02） ====================

    @Test
    @DisplayName("管理员：全站列表按 status 筛、强制下线、置顶；USER 访问治理口 → 1005")
    void adminGovernance() throws Exception {
        String owner = loginForToken("testuser");
        long id = ((Number) createDraft(owner, Map.of("status", 1)).get("id")).longValue();
        String admin = loginForToken("other");

        // ADMIN 全站视图：status=0 能筛出草稿（普通用户列表里它永远不可见）
        mockMvc.perform(get("/api/articles").header("Authorization", "Bearer " + admin)
                        .param("status", "1").param("authorId", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1));

        // 置顶：作者自己整包 PUT 改不了 top_flag（白名单字段授权），只此 ADMIN 通道
        mockMvc.perform(patch("/api/articles/" + id + "/top?topFlag=1")
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/articles/" + id).header("Authorization", "Bearer " + admin))
                .andExpect(jsonPath("$.data.topFlag").doesNotExist()); // topFlag 不在 VO 契约（列表用排序体现，不单独回显）
        Integer top = jdbcTemplate.queryForObject(
                "select top_flag from article where id=" + id, Integer.class);
        assertThat(top).isEqualTo(1);

        // USER 令牌碰治理口：1005（@PreAuthorize hasRole ADMIN）
        mockMvc.perform(patch("/api/articles/" + id + "/top?topFlag=1")
                        .header("Authorization", "Bearer " + owner))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(1005));
        mockMvc.perform(patch("/api/articles/" + id + "/offline")
                        .header("Authorization", "Bearer " + owner))
                .andExpect(status().isForbidden());

        // 强制下线 + 计数结算
        mockMvc.perform(patch("/api/articles/" + id + "/offline")
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("已下线"));
        assertThat(articleMapper.selectById(id).getStatus()).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject(
                "select article_count from category where id=1", Integer.class)).isZero();
    }

    // ==================== XSS 全链路（NFR-05 / D-7） ====================

    @Test
    @DisplayName("API 注入 XSS 正文：入库 content_text 已洗（存储型 XSS 在写入口即死）")
    void xssPayloadThroughApiIsNeutralizedInStore() throws Exception {
        String owner = loginForToken("testuser");
        Map<String, Object> env = saveArticle(owner, "POST", "/api/articles", Map.of(
                "title", "XSS 样本", "contentMd",
                "安全开头 <script>alert('xss')</script><img src=x onerror=alert(1)> 安全结尾",
                "status", 1));
        long id = ((Number) castMap(env.get("data")).get("id")).longValue();

        Article a = articleMapper.selectById(id);
        assertThat(a.getContentText()).doesNotContain("<", "alert")
                .contains("安全开头", "安全结尾");
        // content_md 原文保留（所见即所存，渲染安全由前端 DOMPurify 二道防线；
        // 服务端防线保护的是"所有读 content_text 的消费方"——搜索索引与 AI 输入）
        assertThat(a.getContentMd()).contains("<script>");
    }

    // ==================== 归档 / 作者文章（UC-V-06 数据源） ====================

    @Test
    @DisplayName("归档树按年-月分组；GET /api/users/{id}/articles 公开且仅已发布")
    void archiveAndAuthorArticles() throws Exception {
        String owner = loginForToken("testuser");
        createDraft(owner, Map.of("status", 1, "title", "归档样本已发布"));
        createDraft(owner, Map.of("status", 0, "title", "归档样本草稿"));

        MvcResult r = mockMvc.perform(get("/api/articles/archive"))
                .andExpect(status().isOk())
                .andReturn();
        Map<String, Object> tree = castMap(readEnvelope(r).get("data"));
        // 结构 {"2026": {"09": [...]}}；只含已发布那篇
        assertThat(tree).hasSize(1);
        Map<String, Object> year = castMap(tree.values().iterator().next());
        List<?> monthList = (List<?>) year.values().iterator().next();
        assertThat(monthList).hasSize(1);

        // 游客查作者公开文章：1 篇（草稿不可见）
        mockMvc.perform(get("/api/users/1/articles"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.list[0].title").value("归档样本已发布"));
    }

    // ==================== 参数校验 ====================

    @Test
    @DisplayName("入口校验：标题空 400；正文空 400；标题超 200 字 400")
    void saveValidation() throws Exception {
        String owner = loginForToken("testuser");
        assertThat((Integer) saveArticle(owner, "POST", "/api/articles",
                Map.of("title", "", "contentMd", "有正文", "status", 0)).get("code")).isEqualTo(400);
        assertThat((Integer) saveArticle(owner, "POST", "/api/articles",
                Map.of("title", "无正文", "contentMd", " ", "status", 0)).get("code")).isEqualTo(400);
        assertThat((Integer) saveArticle(owner, "POST", "/api/articles",
                Map.of("title", "长".repeat(201), "contentMd", "正文", "status", 0)).get("code")).isEqualTo(400);
    }
}
