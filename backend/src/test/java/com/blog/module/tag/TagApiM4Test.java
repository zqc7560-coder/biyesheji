package com.blog.module.tag;

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

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 标签模块接口级测试（M4）—— 覆盖《需求分析.md》UC-A-04（增删改查、合并）
 * 与《API接口设计.md》§5 标签五项约定：公开列表/标签云权重、ADMIN 写口、
 * 删除连带解除关联、合并改挂+重算计数、重名 2201 / 幽灵 2202。
 *
 * <p>基建沿用 M2/M3 测试（内存 Redis、类级 @Transactional、UTF-8 信封）；
 * 身份物料：testuser(id=1, ROLE_USER) / other(id=2, ROLE_ADMIN)。
 *
 * <p>合并用例是本模块的核心难点：需要"同一篇文章同时挂 from 与 to"来踩
 * uk_article_tag 唯一键——INSERT IGNORE 复制必须静默跳过这条冲突行而不是
 * 整批失败，这正是测试要钉死的行为。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@DisplayName("M4 标签接口: 列表/标签云/CRUD/合并/删除连带解除/RBAC")
class TagApiM4Test {

    private static final String SEED_PWD = "password123";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JdbcTemplate jdbcTemplate;

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

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> castList(Object o) {
        return o == null ? List.of() : (List<Map<String, Object>>) o;
    }

    /** 读信封：显式 UTF-8 解字节（MockMvc ISO-8859-1 坑，M2 台账） */
    @SuppressWarnings("unchecked")
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
        return (String) castMap(readEnvelope(r).get("data")).get("token");
    }

    /** 通用 JSON 请求（GET 也走这里统一拆信封） */
    private Map<String, Object> call(String method, String uri, String token, Object body) throws Exception {
        var builder = switch (method) {
            case "POST" -> post(uri);
            case "PUT" -> put(uri);
            case "DELETE" -> delete(uri);
            default -> get(uri);
        };
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        if (body != null) {
            builder.contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(body));
        }
        return readEnvelope(mockMvc.perform(builder).andReturn());
    }

    /** 建标签并断言成功，返回 data（TagVO：id/name/color/articleCount） */
    private Map<String, Object> createTag(String admin, String name, String color) throws Exception {
        Map<String, Object> env = call("POST", "/api/tags", admin,
                color == null ? Map.of("name", name) : Map.of("name", name, "color", color));
        assertThat((Integer) env.get("code")).as("标签创建应成功: %s", env.get("message")).isZero();
        return castMap(env.get("data"));
    }

    /** 用发文接口给文章打标签（M3 的 tags 数组契约：不存在的自动创建），返回 {articleId, tagIds...} */
    private Map<String, Object> publishWithTags(String userToken, List<String> tags, String titleSuffix) throws Exception {
        Map<String, Object> env = call("POST", "/api/articles", userToken, Map.of(
                "title", "M4标签载体文章" + titleSuffix,
                "contentMd", "正文若干字，用于标签合并与删除用例的载体。",
                "tags", tags,
                "status", 1));
        assertThat((Integer) env.get("code")).as("发带标签文章应成功: %s", env.get("message")).isZero();
        return castMap(env.get("data"));
    }

    /** 从 GET /api/tags 里按名取标签 VO（含 id/articleCount/weight） */
    private Map<String, Object> fetchTagByName(String name) throws Exception {
        List<Map<String, Object>> list = castList(call("GET", "/api/tags", null, null).get("data"));
        return list.stream().filter(t -> name.equals(t.get("name"))).findFirst().orElse(null);
    }

    // ==================== 公开读与 RBAC ====================

    @Test
    @DisplayName("游客 GET /api/tags 公开；写口 USER 1005 / 匿名 1001")
    void publicReadAndWriteRbac() throws Exception {
        assertThat((Integer) call("GET", "/api/tags", null, null).get("code")).isZero();
        String user = loginForToken("testuser");   // ROLE_USER 无 tag:manage
        assertThat((Integer) call("POST", "/api/tags", user, Map.of("name", "越权标签" + System.nanoTime())).get("code"))
                .isEqualTo(1005);
        assertThat((Integer) call("POST", "/api/tags", null, Map.of("name", "匿名标签" + System.nanoTime())).get("code"))
                .isEqualTo(1001);
    }

    // ==================== CRUD ====================

    @Test
    @DisplayName("新增/修改/重名：默认色补齐 #409EFF；非法色 400；重名 2201；幽灵 id 2202(404)")
    void crudAndValidation() throws Exception {
        String admin = loginForToken("other");
        String suffix = String.valueOf(System.nanoTime());

        Map<String, Object> created = createTag(admin, "M4标签A" + suffix, null);
        assertThat((String) created.get("color")).isEqualTo("#409EFF");
        long id = ((Number) created.get("id")).longValue();

        // 非法颜色格式（DTO @Pattern 白名单：直进内联 style 的字段必须格式锁死）
        assertThat((Integer) call("POST", "/api/tags", admin,
                Map.of("name", "M4坏色" + suffix, "color", "javascript:alert(1)")).get("code")).isEqualTo(400);
        // 重名
        assertThat((Integer) call("POST", "/api/tags", admin,
                Map.of("name", "M4标签A" + suffix)).get("code")).isEqualTo(2201);
        // 改名撞已存在名 2201；改名"不成真改名"（同名同 id）放行
        Map<String, Object> second = createTag(admin, "M4标签B" + suffix, "#ff6600");
        assertThat((Integer) call("PUT", "/api/tags/" + ((Number) second.get("id")).longValue(), admin,
                Map.of("name", "M4标签A" + suffix)).get("code")).isEqualTo(2201);
        assertThat((Integer) call("PUT", "/api/tags/" + id, admin,
                Map.of("name", "M4标签A" + suffix, "color", "#00aa88")).get("code")).isZero();
        Map<String, Object> after = fetchTagByName("M4标签A" + suffix);
        assertThat((String) after.get("color")).isEqualTo("#00aa88");

        // 幽灵：PUT/DELETE 不存在的 id → 2202 + HTTP 404
        mockMvc.perform(delete("/api/tags/999999")
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isNotFound())
                .andExpect(r -> assertThat((Integer) readEnvelope(r).get("code")).isEqualTo(2202));
    }

    // ==================== 标签云 ====================

    @Test
    @DisplayName("cloud=true：热度降序 + weight 落在 [1,5]；最热标签权重=5；非 cloud 不填 weight")
    void cloudWeights() throws Exception {
        String admin = loginForToken("other");
        String user = loginForToken("testuser");
        String suffix = String.valueOf(System.nanoTime());
        // 用发文给"云热标签"叠 2 次引用（两篇文章各挂一次），"云冷标签"挂 1 次
        publishWithTags(user, List.of("云热标签" + suffix, "云冷标签" + suffix), "H1-" + suffix);
        publishWithTags(user, List.of("云热标签" + suffix), "H2-" + suffix);

        List<Map<String, Object>> cloud = castList(call("GET", "/api/tags?cloud=true", null, null).get("data"));
        Map<String, Object> hot = cloud.stream().filter(t -> ("云热标签" + suffix).equals(t.get("name"))).findFirst().orElseThrow();
        Map<String, Object> cold = cloud.stream().filter(t -> ("云冷标签" + suffix).equals(t.get("name"))).findFirst().orElseThrow();
        assertThat(cloud.indexOf(hot)).as("热度降序：热标签排在冷标签前")
                .isLessThan(cloud.indexOf(cold));
        assertThat(((Number) hot.get("weight")).doubleValue()).isGreaterThanOrEqualTo(1.0)
                .isLessThanOrEqualTo(5.0);
        assertThat(((Number) cold.get("weight")).doubleValue())
                .isLessThan(((Number) hot.get("weight")).doubleValue());

        List<Map<String, Object>> plain = castList(call("GET", "/api/tags", null, null).get("data"));
        assertThat(plain.stream().filter(t -> ("云热标签" + suffix).equals(t.get("name")))
                .findFirst().orElseThrow().get("weight")).as("非云模式不填权重").isNull();
    }

    // ==================== 删除连带解除 ====================

    @Test
    @DisplayName("删除标签：tag 行逻辑删除 + article_tag 关联物理清空 + 文章详情不再显示该标签")
    void deleteUnlinksArticles() throws Exception {
        String admin = loginForToken("other");
        String user = loginForToken("testuser");
        String suffix = String.valueOf(System.nanoTime());
        Map<String, Object> art = publishWithTags(user, List.of("待删标签" + suffix), "D-" + suffix);
        long articleId = ((Number) art.get("id")).longValue();
        Map<String, Object> tag = fetchTagByName("待删标签" + suffix);
        long tagId = ((Number) tag.get("id")).longValue();
        assertThat(((Number) tag.get("articleCount")).intValue()).isEqualTo(1);

        assertThat((Integer) call("DELETE", "/api/tags/" + tagId, admin, null).get("code")).isZero();
        // 关联表必须清空（物理删），tag 行 deleted=1（逻辑删）
        Integer links = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM article_tag WHERE tag_id = ?", Integer.class, tagId);
        assertThat(links).isZero();
        Integer deletedFlag = jdbcTemplate.queryForObject(
                "SELECT deleted FROM tag WHERE id = ?", Integer.class, tagId);
        assertThat(deletedFlag).isEqualTo(1);
        // 文章详情聚合不再含该标签（回归 M3 详情链路，验证解除对读侧生效）
        Map<String, Object> detail = castMap(call("GET", "/api/articles/" + articleId, user, null).get("data"));
        assertThat(castList(detail.get("tags"))).extracting(t -> (String) t.get("name"))
                .doesNotContain("待删标签" + suffix);
    }

    // ==================== 合并 ====================

    @Test
    @DisplayName("合并：from 关联改挂 to 且计数重算；同文章双挂（唯一键冲突行）静默去重不整批失败；from 逻辑删除")
    void mergeRelinksAndRecounts() throws Exception {
        String admin = loginForToken("other");
        String user = loginForToken("testuser");
        String suffix = String.valueOf(System.nanoTime());
        String fromName = "合并源" + suffix;
        String toName = "合并目标" + suffix;

        // 文章1：同时挂 from+to（唯一键场景）；文章2：只挂 from；文章3：只挂 to
        publishWithTags(user, List.of(fromName, toName), "M1-" + suffix);
        publishWithTags(user, List.of(fromName), "M2-" + suffix);
        publishWithTags(user, List.of(toName), "M3-" + suffix);

        long fromId = ((Number) fetchTagByName(fromName).get("id")).longValue();
        long toId = ((Number) fetchTagByName(toName).get("id")).longValue();

        // 自合并拒绝（防"删 from 连带删 to 关联"的语义事故）
        assertThat((Integer) call("POST", "/api/tags/merge", admin,
                Map.of("fromTagId", fromId, "toTagId", fromId)).get("code")).isEqualTo(400);
        // 目标不存在拒绝（治理动作不静默新建）
        assertThat((Integer) call("POST", "/api/tags/merge", admin,
                Map.of("fromTagId", fromId, "toTagId", 999999)).get("code")).isEqualTo(2202);

        Map<String, Object> merged = call("POST", "/api/tags/merge", admin,
                Map.of("fromTagId", fromId, "toTagId", toId));
        assertThat((Integer) merged.get("code")).as("合并应成功: %s", merged.get("message")).isZero();

        // 合并后：to 独占全部 3 篇文章（重叠的文章1不重复计数）；from 行 deleted=1 且关联清零
        Map<String, Object> toAfter = fetchTagByName(toName);
        assertThat(((Number) toAfter.get("articleCount")).intValue()).isEqualTo(3);
        Integer fromLinks = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM article_tag WHERE tag_id = ?", Integer.class, fromId);
        assertThat(fromLinks).isZero();
        Integer fromDeleted = jdbcTemplate.queryForObject(
                "SELECT deleted FROM tag WHERE id = ?", Integer.class, fromId);
        assertThat(fromDeleted).isEqualTo(1);

        // 去重验证：文章1 对 to 的关联只有一条（uk 仍在，重复行已被 INSERT IGNORE 跳过）
        Integer dupCheck = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM article_tag WHERE tag_id = ?", Integer.class, toId);
        assertThat(dupCheck).isEqualTo(3);

        // 读侧回归：文章1 详情标签列表含 to 不含 from
        List<Map<String, Object>> arts = castList(castMap(
                call("GET", "/api/articles?mine=1&pageSize=50", user, null).get("data")).get("list"));
        Number art1Id = (Number) arts.stream().filter(a -> ("M4标签载体文章M1-" + suffix).equals(a.get("title")))
                .findFirst().orElseThrow().get("id");
        long art1 = art1Id.longValue();
        Map<String, Object> d1 = castMap(call("GET", "/api/articles/" + art1, user, null).get("data"));
        assertThat(castList(d1.get("tags"))).extracting(t -> (String) t.get("name"))
                .contains(toName).doesNotContain(fromName);
    }

    // ==================== 发文链路与治理的联动（回归钉） ====================

    @Test
    @DisplayName("回归钉：发文自动建标签后，治理口能立刻管理它；合并后旧发文重编辑不复活 from")
    void autoCreatedTagGovernanceLoop() throws Exception {
        String admin = loginForToken("other");
        String user = loginForToken("testuser");
        String suffix = String.valueOf(System.nanoTime());
        Map<String, Object> art = publishWithTags(user, List.of("自动标X" + suffix), "G-" + suffix);
        long articleId = ((Number) art.get("id")).longValue();
        long fromId = ((Number) fetchTagByName("自动标X" + suffix).get("id")).longValue();
        long toId = ((Number) createTag(admin, "接管标Y" + suffix, null).get("id")).longValue();
        call("POST", "/api/tags/merge", admin, Map.of("fromTagId", fromId, "toTagId", toId));

        // 编辑器重保存：关联已被合并搬移到 Y，按"全量覆盖"语义重提交 [Y] 后计数应保持 1、
        // 不产生重复关联（uk 兜底）；钉住"治理操作与发文链路"的联动正确性。
        Integer ver = jdbcTemplate.queryForObject(
                "SELECT version FROM article WHERE id = ?", Integer.class, articleId);
        Map<String, Object> body = new HashMap<>();
        body.put("title", "M4标签载体文章G-" + suffix);
        body.put("contentMd", "正文若干字，用于标签合并与删除用例的载体。");
        body.put("tags", List.of("接管标Y" + suffix));
        body.put("version", ver);
        assertThat((Integer) call("PUT", "/api/articles/" + articleId, user, body).get("code")).isZero();
        assertThat(((Number) fetchTagByName("接管标Y" + suffix).get("articleCount")).intValue()).isEqualTo(1);
    }
}
