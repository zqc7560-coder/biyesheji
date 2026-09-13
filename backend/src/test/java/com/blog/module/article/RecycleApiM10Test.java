package com.blog.module.article;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.blog.module.article.mapper.ArticleMapper;
import com.blog.support.InMemoryRedis;
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

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 回收站接口测试（M10）—— 覆盖《需求分析.md》UC-U-09 的后半句
 * 「删除为逻辑删除，管理员可在回收站彻底删除」，以及台账 M3 备注里顺延的
 * 「管理员回收站/彻底删除通道随 M10 后台打通」。
 *
 * <p>钉死的行为契约（每条都对应一个真实风险）：
 * <ol>
 *   <li>RBAC：trash/restore/purge 三口 USER 令牌与匿名一律 403/401——回收站装着
 *       全站已删内容（可能含违规文），泄漏面比正常列表更大；</li>
 *   <li>可见性隔离：已删文章不出现在公开列表/mine 列表/详情（deleted=0 拼接的
 *       回归防线——自定义 SQL 只开给 /trash 一个口）；</li>
 *   <li>恢复语义：deleted 翻回 0、<b>status 保持删除前原值</b>（草稿恢复后不能变成
 *       已发布——那是替作者做发布决定）；分类计数 +1 回补、幂等二调不双计；</li>
 *   <li>彻底删除护栏：对"未删除"的文章执行 purge 必须 2001 拒（防手滑），
 *       行真从库里消失（COUNT 查物理表）、其下评论 deleted=1；</li>
 *   <li>幽灵 id：trash 里没有的行 restore/purge 均 2001。</li>
 * </ol>
 *
 * <p>基建与 M3 同款：内存 Redis @Primary、类级 @Transactional 回滚、readEnvelope
 * 显式 UTF-8（MockMvc ISO-8859-1 坑）。身份物料：testuser(id=1, USER)、
 * other(id=2, ADMIN)，口令 password123。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@DisplayName("M10 回收站: RBAC/隔离/恢复语义/彻底删除护栏/幂等")
class RecycleApiM10Test {

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

    // ==================== 工具（与 M3 测试同构） ====================

    @SuppressWarnings("unchecked")
    private Map<String, Object> castMap(Object o) {
        return (Map<String, Object>) o;
    }

    private Map<String, Object> readEnvelope(MvcResult result) throws Exception {
        return objectMapper.readValue(
                new String(result.getResponse().getContentAsByteArray(),
                        java.nio.charset.StandardCharsets.UTF_8),
                Map.class);
    }

    private String loginForToken(String username) throws Exception {
        MvcResult r = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("username", username, "password", SEED_PWD))))
                .andReturn();
        assertThat(r.getResponse().getStatus()).as("登录 %s 应 200", username).isEqualTo(200);
        return (String) castMap(readEnvelope(r).get("data")).get("token");
    }

    /** 建一篇文章（可覆盖字段），返回 id */
    private long createArticle(String token, Map<String, Object> overrides) throws Exception {
        Map<String, Object> body = new HashMap<>(Map.of(
                "title", "回收站样本文章 " + System.nanoTime(),
                "categoryId", 1,
                "tags", List.of("回收站专项"),
                "contentMd", "# 样本\n\n正文内容，用于回收站全链路验证。",
                "status", 0,
                "allowComment", 1));
        if (overrides != null) body.putAll(overrides);
        MvcResult r = mockMvc.perform(post("/api/articles")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
        Map<String, Object> env = readEnvelope(r);
        assertThat((Integer) env.get("code")).as("创建应成功: %s", env.get("message")).isZero();
        return ((Number) castMap(env.get("data")).get("id")).longValue();
    }

    /** 逻辑删除（走真实 DELETE 接口，覆盖"删除→进回收站"的上游链路） */
    private void deleteArticle(String token, long id) throws Exception {
        mockMvc.perform(delete("/api/articles/" + id)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    /** 往某文章塞一条已通过评论（走 SQL 直插——测的是"彻底删除连带逻辑删评论"，
     *  发表链路 M5 已有专测；status=1 让它落在"会显示"的口径上） */
    private void seedApprovedComment(long articleId, long userId) {
        // 注意：前置写库走 JdbcTemplate 后若同事务内还要经 MP 读该表，须防一级缓存
        // 旧值（M6/M8 台账坑）。本用例里 comment 表在 purge 前没有任何 MP 读操作，
        // 验证 deleted 位也用 JdbcTemplate 直查——不跨缓存层读，无坑可踩。
        jdbcTemplate.update(
                "INSERT INTO comment (article_id, user_id, parent_id, content, status, deleted) " +
                        "VALUES (?, ?, 0, '写得好！', 1, 0)", articleId, userId);
    }

    private int intOf(Object o) {
        return ((Number) o).intValue();
    }

    // ==================== 用例 ====================

    @Test
    @DisplayName("RBAC：回收站三口 USER 令牌→403、匿名→401（已删内容只对 ADMIN 可见）")
    void trashEndpointsAreAdminOnly() throws Exception {
        String user = loginForToken("testuser");
        String anonExpected401 = "匿名请求任何 /api/articles/trash* 都必须 401";

        mockMvc.perform(get("/api/articles/trash").header("Authorization", "Bearer " + user))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/articles/1/restore").header("Authorization", "Bearer " + user))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete("/api/articles/1/purge").header("Authorization", "Bearer " + user))
                .andExpect(status().isForbidden());

        MvcResult anon = mockMvc.perform(get("/api/articles/trash")).andReturn();
        assertThat(anon.getResponse().getStatus()).as(anonExpected401).isEqualTo(401);
    }

    @Test
    @DisplayName("列表隔离：已删文章进 /trash、出公开列表、出 mine 列表、详情 2001")
    void deletedArticleOnlyVisibleInTrash() throws Exception {
        String owner = loginForToken("testuser");
        String admin = loginForToken("other");
        long id = createArticle(owner, Map.of("status", 1));
        deleteArticle(owner, id);

        // 详情：作者本人都拿 2001（不可见即不存在，防枚举口径贯穿到"已删"态）
        mockMvc.perform(get("/api/articles/" + id).header("Authorization", "Bearer " + owner))
                .andExpect(status().isNotFound());

        // 回收站：ADMIN 能看到它，且带作者昵称（toBriefVOList 聚合装配的回归）
        MvcResult t = mockMvc.perform(get("/api/articles/trash")
                        .header("Authorization", "Bearer " + admin).param("pageSize", "100"))
                .andReturn();
        Map<String, Object> env = readEnvelope(t);
        assertThat(intOf(env.get("code"))).isZero();
        List<Map<String, Object>> list = (List<Map<String, Object>>) castMap(env.get("data")).get("list");
        assertThat(list).anySatisfy(row -> {
            assertThat(intOf(row.get("id"))).isEqualTo((int) id);
            assertThat(castMap(row.get("author")).get("nickname")).isEqualTo("测试用户");
        });

        // mine 列表：删除即从草稿箱消失（@TableLogic 拼接未被自定义通道污染的回归断言）
        MvcResult mine = mockMvc.perform(get("/api/articles")
                        .header("Authorization", "Bearer " + owner).param("mine", "1").param("pageSize", "100"))
                .andReturn();
        List<Map<String, Object>> mineList = (List<Map<String, Object>>)
                castMap(readEnvelope(mine).get("data")).get("list");
        assertThat(mineList).noneSatisfy(row -> assertThat(intOf(row.get("id"))).isEqualTo((int) id));

        // 公开列表同理（游客可访问，绝不含已删行）
        MvcResult pub = mockMvc.perform(get("/api/articles").param("pageSize", "100")).andReturn();
        List<Map<String, Object>> pubList = (List<Map<String, Object>>)
                castMap(readEnvelope(pub).get("data")).get("list");
        assertThat(pubList).noneSatisfy(row -> assertThat(intOf(row.get("id"))).isEqualTo((int) id));
    }

    @Test
    @DisplayName("恢复：deleted 翻 0、status 保持原值、分类计数回补、幂等二调不双计")
    void restoreKeepsStatusAndIsIdempotent() throws Exception {
        String owner = loginForToken("testuser");
        String admin = loginForToken("other");
        // 已发布 + 分类 1：删除时分类计数 -1，恢复必须回补（GREATEST 钳位的镜像）
        long id = createArticle(owner, Map.of("status", 1));
        int countAfterPublish = jdbcTemplate.queryForObject(
                "SELECT article_count FROM category WHERE id = 1", Integer.class);
        deleteArticle(owner, id);
        int countAfterDelete = jdbcTemplate.queryForObject(
                "SELECT article_count FROM category WHERE id = 1", Integer.class);
        assertThat(countAfterDelete).as("删除发布文应 -1").isEqualTo(countAfterPublish - 1);

        MvcResult r = mockMvc.perform(post("/api/articles/" + id + "/restore")
                        .header("Authorization", "Bearer " + admin))
                .andReturn();
        Map<String, Object> env = readEnvelope(r);
        assertThat(intOf(env.get("code"))).isZero();
        assertThat(env.get("message")).isEqualTo("已恢复");

        // 行回到库里且状态原样是"已发布"（恢复不是发布决定）
        Integer deleted = jdbcTemplate.queryForObject(
                "SELECT deleted FROM article WHERE id = ?", Integer.class, id);
        Integer status = jdbcTemplate.queryForObject(
                "SELECT status FROM article WHERE id = ?", Integer.class, id);
        assertThat(deleted).isZero();
        assertThat(status).as("恢复后必须保持删除前的发布态").isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT article_count FROM category WHERE id = 1", Integer.class))
                .as("分类计数回补").isEqualTo(countAfterDelete + 1);

        // 幂等：再调一次 restore → "文章已在库中"，计数不再 +1
        // （selectTrashedById 查不到 deleted=0 的行会先抛 2001——两种表现都可接受，
        //  关键是绝不能把已恢复的文章再结算一次计数）
        MvcResult again = mockMvc.perform(post("/api/articles/" + id + "/restore")
                .header("Authorization", "Bearer " + admin)).andReturn();
        Map<String, Object> env2 = readEnvelope(again);
        assertThat(intOf(env2.get("code")))
                .as("重复恢复要么幂等成功要么 2001，不许 500").isIn(0, 2001);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT article_count FROM category WHERE id = 1", Integer.class))
                .as("无论幂等分支走哪条，计数都不得二次累加").isEqualTo(countAfterDelete + 1);

        // 恢复后作者详情可读（可见性矩阵回归）
        mockMvc.perform(get("/api/articles/" + id).header("Authorization", "Bearer " + owner))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("草稿删除后恢复：status=0 不升格为发布（治理动作不改内容语义）")
    void restoreDraftStaysDraft() throws Exception {
        String owner = loginForToken("testuser");
        String admin = loginForToken("other");
        long id = createArticle(owner, null);   // status=0 草稿
        deleteArticle(owner, id);
        mockMvc.perform(post("/api/articles/" + id + "/restore")
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM article WHERE id = ?", Integer.class, id))
                .as("草稿恢复后仍是草稿").isZero();
    }

    @Test
    @DisplayName("彻底删除：物理行消失+评论逻辑删；对未删除文章执行 purge 必须 2001 拒")
    void purgeDeletesRowButGuardedByTrashedState() throws Exception {
        String owner = loginForToken("testuser");
        String admin = loginForToken("other");
        long id = createArticle(owner, Map.of("status", 1));
        seedApprovedComment(id, 2);

        // 护栏：文章还活着（deleted=0）→ purge 必须拒（防手滑物理删在线内容）
        MvcResult guard = mockMvc.perform(delete("/api/articles/" + id + "/purge")
                .header("Authorization", "Bearer " + admin)).andReturn();
        assertThat(intOf(readEnvelope(guard).get("code"))).isEqualTo(2001);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM article WHERE id = ?", Integer.class, id))
                .as("被拒的 purge 一行都不许少").isEqualTo(1);

        // 正常链路：逻辑删 → 进回收站 → 彻底删
        deleteArticle(owner, id);
        mockMvc.perform(delete("/api/articles/" + id + "/purge")
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk());

        // 物理行真的没了（不是又叠一层 deleted=1）——绕过 @TableLogic 直查物理表
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM article WHERE id = ?", Integer.class, id))
                .as("purge 后行必须物理消失").isZero();
        // 其下评论被逻辑删（留审计痕迹的口径）
        assertThat(jdbcTemplate.queryForObject(
                "SELECT deleted FROM comment WHERE article_id = ?", Integer.class, id))
                .as("连带评论 deleted=1").isEqualTo(1);
        // 回收站里也没它了
        MvcResult t = mockMvc.perform(get("/api/articles/trash")
                        .header("Authorization", "Bearer " + admin).param("pageSize", "100"))
                .andReturn();
        List<Map<String, Object>> list = (List<Map<String, Object>>)
                castMap(readEnvelope(t).get("data")).get("list");
        assertThat(list).noneSatisfy(row -> assertThat(intOf(row.get("id"))).isEqualTo((int) id));
    }

    @Test
    @DisplayName("幽灵 id：不存在的文章 restore/purge 均 2001（不 500、不静默成功）")
    void ghostIdRejected() throws Exception {
        String admin = loginForToken("other");
        mockMvc.perform(post("/api/articles/99999999/restore")
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isNotFound());
        mockMvc.perform(delete("/api/articles/99999999/purge")
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isNotFound());
    }
}
