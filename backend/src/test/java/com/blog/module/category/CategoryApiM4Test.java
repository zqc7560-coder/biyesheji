package com.blog.module.category;

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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 分类模块接口级测试（M4）—— 覆盖《需求分析.md》UC-A-03（增删改查与排序、
 * 有文章禁删）与《API接口设计.md》§5 分类八项约定：公开树、ADMIN 写口、
 * 两级约束（§3.7）、删除双守卫（2101/2102）、重名 2103。
 *
 * <p>基建沿用 M2/M3：内存 Redis @Primary 覆盖、类级 @Transactional 回滚零污染、
 * readEnvelope 显式 UTF-8（MockMvc ISO-8859-1 坑，台账记录）。
 *
 * <p>身份物料（data-h2.sql 种子）：testuser(id=1, ROLE_USER 三权限，无 category:manage)、
 * other(id=2, ROLE_ADMIN 全权限)。种子分类 id=1 技术 / 2 生活 / 3 随笔（顶级）。
 *
 * <p>断言全部"包含关系"式（不数绝对 total——M3 台账坑①：共享 blog_test 库
 * 存在跨用例提交的样本，绝对计数不可靠）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@DisplayName("M4 分类接口: 公开树/两级约束/重名/删除双守卫/RBAC")
class CategoryApiM4Test {

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

    /** 读信封：显式 UTF-8 解字节（M2 台账坑） */
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

    /** 发一个 JSON 写请求（method ∈ POST/PUT/DELETE），返回信封 */
    private Map<String, Object> call(String method, String uri, String token, Object body) throws Exception {
        var builder = switch (method) {
            case "POST" -> post(uri);
            case "PUT" -> put(uri);
            case "DELETE" -> delete(uri);
            default -> throw new IllegalArgumentException(method);
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

    /** 新建分类并断言成功，返回 data 节点（含 id/slug 回显） */
    private Map<String, Object> createCategory(String adminToken, Map<String, Object> body) throws Exception {
        Map<String, Object> env = call("POST", "/api/categories", adminToken, body);
        assertThat((Integer) env.get("code")).as("分类创建应成功: %s", env.get("message")).isZero();
        return castMap(env.get("data"));
    }

    /** GET /api/categories 树（匿名），返回根列表 */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> fetchTree() throws Exception {
        Map<String, Object> env = readEnvelope(mockMvc.perform(get("/api/categories")).andReturn());
        assertThat((Integer) env.get("code")).isZero();
        return (List<Map<String, Object>>) env.get("data");
    }

    /** 在树里按 id 找节点（含 children 一层递归），找不到返回 null */
    private Map<String, Object> findNode(List<Map<String, Object>> nodes, long id) {
        for (Map<String, Object> n : nodes) {
            if (((Number) n.get("id")).longValue() == id) {
                return n;
            }
            Map<String, Object> hit = findNode(castMapList(n.get("children")), id);
            if (hit != null) {
                return hit;
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> castMapList(Object o) {
        return o == null ? List.of() : (List<Map<String, Object>>) o;
    }

    // ==================== 公开读 ====================

    @Test
    @DisplayName("游客可取分类树：种子三分类在列且为顶级、children 恒为数组、含 articleCount 契约字段")
    void publicTree() throws Exception {
        List<Map<String, Object>> tree = fetchTree();
        // 包含关系断言（台账坑①）：三个种子顶级分类必须都在
        assertThat(tree).extracting(n -> (String) n.get("name"))
                .contains("技术", "生活", "随笔");
        Map<String, Object> tech = tree.stream().filter(n -> "技术".equals(n.get("name"))).findFirst().orElseThrow();
        assertThat(tech).containsKeys("id", "slug", "sortOrder", "articleCount", "children");
        assertThat(((Number) tech.get("parentId")).longValue()).isZero();
        assertThat(tech.get("children")).isInstanceOf(List.class);
    }

    // ==================== 新增 ====================

    @Test
    @DisplayName("ADMIN 新增：重名 2103；slug 留空自动生成 cat-{id} 占位；parentId 缺省=0")
    void createByAdmin() throws Exception {
        String admin = loginForToken("other");
        Map<String, Object> created = createCategory(admin, Map.of("name", "M4新建分类" + System.nanoTime(), "sortOrder", 9));
        assertThat(((Number) created.get("parentId")).longValue()).isZero();
        assertThat((String) created.get("slug")).startsWith("cat-");

        Map<String, Object> dup = call("POST", "/api/categories", admin, Map.of("name", "技术"));
        assertThat((Integer) dup.get("code")).isEqualTo(2103);
    }

    @Test
    @DisplayName("写口 RBAC：USER 令牌 403(1005)、匿名 401(1001)——公开放行只覆盖 GET")
    void writeRequiresAdminPerm() throws Exception {
        String user = loginForToken("testuser");   // ROLE_USER 无 category:manage
        Map<String, Object> byUser = call("POST", "/api/categories", user, Map.of("name", "越权分类" + System.nanoTime()));
        assertThat((Integer) byUser.get("code")).isEqualTo(1005);
        Map<String, Object> anon = call("POST", "/api/categories", null, Map.of("name", "匿名分类" + System.nanoTime()));
        assertThat((Integer) anon.get("code")).isEqualTo(1001);
    }

    @Test
    @DisplayName("两级约束：父不存在→2003；父是二级→400；自环→400")
    void parentConstraints() throws Exception {
        String admin = loginForToken("other");
        Map<String, Object> top = createCategory(admin, Map.of("name", "M4顶级" + System.nanoTime()));
        long topId = ((Number) top.get("id")).longValue();
        Map<String, Object> sub = createCategory(admin, Map.of("name", "M4子级" + System.nanoTime(), "parentId", topId));
        long subId = ((Number) sub.get("id")).longValue();

        // 挂在二级下 → 三级，拒
        assertThat((Integer) call("POST", "/api/categories", admin,
                Map.of("name", "M4三级" + System.nanoTime(), "parentId", subId)).get("code")).isEqualTo(400);
        // 父不存在 → 2003（HTTP 404）
        Map<String, Object> ghost = call("POST", "/api/categories", admin,
                Map.of("name", "M4孤儿" + System.nanoTime(), "parentId", 999999));
        assertThat((Integer) ghost.get("code")).isEqualTo(2003);
        // 自环：PUT parentId=自身
        assertThat((Integer) call("PUT", "/api/categories/" + topId, admin,
                Map.of("name", "M4顶级改", "parentId", topId)).get("code")).isEqualTo(400);
    }

    // ==================== 树装配 ====================

    @Test
    @DisplayName("嵌套与孤儿提升：子分类挂进父节点 children；父已删的孤儿二级提升为顶级输出（宁可见不可丢）")
    void treeAssembly() throws Exception {
        String admin = loginForToken("other");
        Map<String, Object> top = createCategory(admin, Map.of("name", "M4装配父" + System.nanoTime()));
        long topId = ((Number) top.get("id")).longValue();
        createCategory(admin, Map.of("name", "M4装配子", "parentId", topId, "sortOrder", 2));
        createCategory(admin, Map.of("name", "M4装配子2", "parentId", topId, "sortOrder", 1));

        Map<String, Object> parentNode = findNode(fetchTree(), topId);
        assertThat(parentNode).isNotNull();
        List<Map<String, Object>> children = castMapList(parentNode.get("children"));
        assertThat(children).extracting(c -> (String) c.get("name"))
                .containsExactly("M4装配子2", "M4装配子");   // sortOrder 1 < 2

        // 孤儿场景：直插一个父不存在的二级（模拟历史脏数据/绕过接口写入），断言其升为顶级而非丢失
        long orphanId = ((Number) createCategory(admin, Map.of("name", "M4孤儿样本" + System.nanoTime())).get("id")).longValue();
        jdbcTemplate.update("UPDATE category SET parent_id = 888888 WHERE id = ?", orphanId);
        assertThat(findNode(fetchTree(), orphanId)).as("孤儿分类必须可见（提升顶级）").isNotNull();
    }

    // ==================== 修改 ====================

    @Test
    @DisplayName("修改：改名生效、null 字段不变更、带子级的分类不许降级挂到别的顶级下(2102)、不存在 id→2003")
    void updateRules() throws Exception {
        String admin = loginForToken("other");
        Map<String, Object> top = createCategory(admin, Map.of("name", "M4待改" + System.nanoTime(), "sortOrder", 5));
        long topId = ((Number) top.get("id")).longValue();
        createCategory(admin, Map.of("name", "M4被挂", "parentId", topId));

        // 只传 name：sortOrder 保持 5（null-skip）
        assertThat((Integer) call("PUT", "/api/categories/" + topId, admin, Map.of("name", "M4已改")).get("code")).isZero();
        Map<String, Object> node = findNode(fetchTree(), topId);
        assertThat((String) node.get("name")).isEqualTo("M4已改");
        assertThat(((Number) node.get("sortOrder")).intValue()).isEqualTo(5);

        // 有子级 → 挂到另一个顶级 = 三级，2102
        Map<String, Object> another = createCategory(admin, Map.of("name", "M4另一顶级" + System.nanoTime()));
        Map<String, Object> denied = call("PUT", "/api/categories/" + topId, admin,
                Map.of("name", "M4已改", "parentId", ((Number) another.get("id")).longValue()));
        assertThat((Integer) denied.get("code")).isEqualTo(2102);

        // 幽灵 id → 2003 + HTTP 404
        mockMvc.perform(put("/api/categories/999999")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("name", "幽灵改"))))
                .andExpect(status().isNotFound())
                .andExpect(result -> assertThat((Integer) readEnvelope(result).get("code")).isEqualTo(2003));
    }

    // ==================== 删除双守卫 ====================

    @Test
    @DisplayName("删除：有文章→2101（文章删光后可删）；有子分类→2102；成功后不再出现在树且库内 deleted=1")
    void deleteGuards() throws Exception {
        String admin = loginForToken("other");
        String user = loginForToken("testuser");
        Map<String, Object> cat = createCategory(admin, Map.of("name", "M4删除守卫" + System.nanoTime()));
        long catId = ((Number) cat.get("id")).longValue();

        // 1) 该分类下建草稿 → 删除被拦 2101（守卫数的是"全部未删除文章"，草稿也拦）
        Map<String, Object> artEnv = call("POST", "/api/articles", user, Map.of(
                "title", "M4守卫文章",
                "categoryId", catId,
                "contentMd", "正文内容若干字，用于分类删除守卫用例。",
                "status", 0));
        assertThat((Integer) artEnv.get("code")).as("草稿创建应成功: %s", artEnv.get("message")).isZero();
        long articleId = ((Number) castMap(artEnv.get("data")).get("id")).longValue();
        assertThat((Integer) call("DELETE", "/api/categories/" + catId, admin, null).get("code")).isEqualTo(2101);

        // 2) 有子分类同样拦 2102（先建子，即便无文章）
        Map<String, Object> child = createCategory(admin, Map.of("name", "M4守卫子", "parentId", catId));
        call("DELETE", "/api/articles/" + articleId, user, null);   // 清掉文章，单独验子守卫
        assertThat((Integer) call("DELETE", "/api/categories/" + catId, admin, null).get("code")).isEqualTo(2102);

        // 3) 子分类自身可删；删完父就干净了 → 删除成功，树内消失、库内留逻辑删除痕
        assertThat((Integer) call("DELETE", "/api/categories/" + ((Number) child.get("id")).longValue(), admin, null).get("code")).isZero();
        assertThat((Integer) call("DELETE", "/api/categories/" + catId, admin, null).get("code")).isZero();
        assertThat(findNode(fetchTree(), catId)).isNull();
        Integer deletedFlag = jdbcTemplate.queryForObject(
                "SELECT deleted FROM category WHERE id = ?", Integer.class, catId);
        assertThat(deletedFlag).as("逻辑删除：行必须还在、标记置 1").isEqualTo(1);
    }
}
