package com.blog.module.comment;

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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 评论模块接口级测试（M5）—— 覆盖《项目计划.md》阶段五验收：
 * "提交→审核→展示"全流程 + 两级结构 + 删除治理 + 冗余计数 + 禁言/XSS。
 *
 * <p>基建沿用 M2/M3/M4 测试（内存 Redis、类级 @Transactional、UTF-8 信封读取）。
 * 身份物料：testuser(id=1, ROLE_USER) / other(id=2, ROLE_ADMIN) / 现场注册新号。
 *
 * <p>断言纪律（M3 台账坑#1）：同 JVM 共享 blog_test 库，M1 roundtrip 用例会向
 * comment 表提交样本行——所有断言只按"本轮自造的文章 id"过滤，绝不做全表绝对值断言。
 *
 * <p>防刷屏闸门（同人同文章 10s）会拦住多条评论的测试前置数据，统一用
 * {@link #ageComment} 把先行 created_at 拨回过去——顺带真实覆盖了闸门的时间判定 SQL。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@DisplayName("M5 评论接口: 发表/两级/审核/删除级联/计数/禁言/XSS")
class CommentApiM5Test {

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

    /** 现场注册一个全新 ROLE_USER 并登录，返回 [token, userId字符串]（禁言/第三方视角用例用）。
     *  用户名 m5u+12 位纳秒取模（注册 DTO 上限 20 字符，nanoTime 原值 19 位会撞长度校验） */
    private String[] registerFreshUser() throws Exception {
        String username = "m5u" + (System.nanoTime() % 1_000_000_000_000L);
        Map<String, Object> env = call("POST", "/api/auth/register", null, Map.of(
                "username", username, "password", SEED_PWD,
                "nickname", "M5路人", "email", username + "@blog.local"));
        assertThat((Integer) env.get("code")).as("注册应成功: %s", env.get("message")).isZero();
        String uid = String.valueOf(castMap(env.get("data")).get("userId"));
        return new String[]{loginForToken(username), uid};
    }

    private Map<String, Object> call(String method, String uri, String token, Object body) throws Exception {
        var builder = switch (method) {
            case "POST" -> post(uri);
            case "PATCH" -> patch(uri);
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

    /** 发一篇文章（allowComment 可空=默认允许），返回文章 id */
    private long publishArticle(String token, String titleSuffix, Integer allowComment) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("title", "M5评论载体文章" + titleSuffix);
        body.put("contentMd", "正文内容，用于评论测试载体。");
        body.put("status", 1);
        if (allowComment != null) {
            body.put("allowComment", allowComment);
        }
        Map<String, Object> env = call("POST", "/api/articles", token, body);
        assertThat((Integer) env.get("code")).as("发文应成功: %s", env.get("message")).isZero();
        return ((Number) castMap(env.get("data")).get("id")).longValue();
    }

    /** 发一条顶级评论（断言业务码 0），返回响应 data（{pending, comment}） */
    private Map<String, Object> comment(String token, long articleId, String content) throws Exception {
        Map<String, Object> env = call("POST", "/api/comments", token, Map.of(
                "articleId", articleId, "content", content));
        assertThat((Integer) env.get("code")).as("评论应成功: %s", env.get("message")).isZero();
        return castMap(env.get("data"));
    }

    /** 取 data.comment.id（直发模式下的评论对象 id） */
    private long idOfCommentVO(Map<String, Object> data) {
        return ((Number) castMap(data.get("comment")).get("id")).longValue();
    }

    /** 把评论 created_at 拨回 seconds 秒前（越过 10s 防刷屏闸门做多条前置数据） */
    private void ageComment(long id, int seconds) {
        jdbcTemplate.update("UPDATE comment SET created_at = DATEADD('SECOND', ?, CURRENT_TIMESTAMP) WHERE id = ?",
                -seconds, id);
    }

    /** 游客读某文章公开评论区（返回顶级列表） */
    private List<Map<String, Object>> publicComments(long articleId) throws Exception {
        Map<String, Object> env = call("GET", "/api/comments?articleId=" + articleId, null, null);
        assertThat((Integer) env.get("code")).isZero();
        return castList(castMap(env.get("data")).get("list"));
    }

    /** 文章详情回显的 comment_count（计数账实核对的读侧探针） */
    private int commentCountOf(long articleId, String token) throws Exception {
        Map<String, Object> detail = castMap(call("GET", "/api/articles/" + articleId, token, null).get("data"));
        return ((Number) detail.get("commentCount")).intValue();
    }

    // ==================== 公开读与 RBAC ====================

    @Test
    @DisplayName("游客可读评论区；匿名 POST 1001；mine=1 匿名 1001；USER 进管理端 1005")
    void publicReadAndAuthGates() throws Exception {
        String user = loginForToken("testuser");
        long art = publishArticle(user, "-gate", null);

        assertThat(publicComments(art)).as("空评论区正常返回").isEmpty();
        assertThat((Integer) call("POST", "/api/comments", null,
                Map.of("articleId", art, "content", "匿名评论")).get("code")).isEqualTo(1001);
        // 白名单放行的是"评论区"GET，不是任意用户的评论史：mine=1 匿名显式 1001
        assertThat((Integer) call("GET", "/api/comments?mine=1", null, null).get("code")).isEqualTo(1001);
        // ROLE_USER 无 comment:audit 权限点 → 1005
        assertThat((Integer) call("GET", "/api/admin/comments", user, null).get("code")).isEqualTo(1005);
    }

    // ==================== 直发模式（审核开关关） ====================

    @Test
    @DisplayName("直发：message=发表成功 + data 含评论对象(status=1) + 公开可见 + comment_count+1")
    void directPublishIncrementsCount() throws Exception {
        String user = loginForToken("testuser");
        long art = publishArticle(user, "-direct", null);

        Map<String, Object> env = call("POST", "/api/comments", user,
                Map.of("articleId", art, "content", "写得好！"));
        assertThat((Integer) env.get("code")).isZero();
        assertThat((String) env.get("message")).isEqualTo("评论发表成功");
        Map<String, Object> data = castMap(env.get("data"));
        assertThat((Boolean) data.get("pending")).isFalse();
        Map<String, Object> created = castMap(data.get("comment"));
        assertThat((Integer) created.get("status")).isEqualTo(1);
        assertThat(castMap(created.get("user")).get("nickname")).isEqualTo("测试用户");

        List<Map<String, Object>> list = publicComments(art);
        assertThat(list).hasSize(1);
        assertThat((String) list.get(0).get("content")).isEqualTo("写得好！");
        assertThat(commentCountOf(art, user)).isEqualTo(1);
    }

    // ==================== 两级结构 ====================

    @Test
    @DisplayName("两级：回复嵌套 replies + replyTo 自动@父作者；回复的回复/跨文章父/幽灵父 均 2302；计数=2")
    void twoLevelStructure() throws Exception {
        String user = loginForToken("testuser");
        String other = loginForToken("other");
        long art = publishArticle(user, "-tree", null);
        long art2 = publishArticle(user, "-tree2", null);

        long top = idOfCommentVO(comment(user, art, "顶级评论A"));
        ageComment(top, 60); // 越过闸门 + 让"回复"排在顶级之后

        // B 以回复身份挂在 A 下（用另一账号，避开同人同文章 10s 闸门）
        Map<String, Object> replyData = call("POST", "/api/comments", other, Map.of(
                "articleId", art, "parentId", top, "content", "同意！"));
        assertThat((Integer) replyData.get("code")).isZero();
        Map<String, Object> reply = castMap(castMap(replyData.get("data")).get("comment"));
        // replyToUid 缺省自动 @ 父评论作者（testuser id=1），"x 回复 y" 有数据可渲染
        assertThat(((Number) castMap(reply.get("replyTo")).get("id")).longValue()).isEqualTo(1L);
        assertThat((String) castMap(reply.get("replyTo")).get("nickname")).isEqualTo("测试用户");

        long replyId = ((Number) reply.get("id")).longValue();
        // 回复的回复 → 2302（两级封顶写入侧执法）
        assertThat((Integer) call("POST", "/api/comments", user, Map.of(
                "articleId", art, "parentId", replyId, "content", "三级尝试")).get("code")).isEqualTo(2302);
        // 跨文章父 → 2302（借刀传声防御：B 文章的评论不能当 A 文章的父）
        assertThat((Integer) call("POST", "/api/comments", other, Map.of(
                "articleId", art2, "parentId", top, "content", "跨文章回复")).get("code")).isEqualTo(2302);
        // 幽灵父 → 2302（用 testuser：other 在本文章刚发过回复会撞 10s 刷屏闸门变 400）
        assertThat((Integer) call("POST", "/api/comments", user, Map.of(
                "articleId", art, "parentId", 987654L, "content", "幽灵父")).get("code")).isEqualTo(2302);

        // 嵌套装配：顶级携带 replies=[回复]；回复不单列在顶层
        List<Map<String, Object>> list = publicComments(art);
        assertThat(list).hasSize(1);
        Map<String, Object> topVo = list.get(0);
        assertThat(((Number) topVo.get("id")).longValue()).isEqualTo(top);
        assertThat(castList(topVo.get("replies"))).hasSize(1);
        assertThat((String) castMap(castList(topVo.get("replies")).get(0)).get("content")).isEqualTo("同意！");
        assertThat(commentCountOf(art, user)).as("顶级+回复都计入 comment_count").isEqualTo(2);
    }

    // ==================== 文章闸门 ====================

    @Test
    @DisplayName("闸门：allowComment=0→2301；草稿评论区游客 2001；作者可评自己草稿")
    void articleGates() throws Exception {
        String user = loginForToken("testuser");
        long closed = publishArticle(user, "-closed", 0);
        assertThat((Integer) call("POST", "/api/comments", user,
                Map.of("articleId", closed, "content", "能评吗")).get("code")).isEqualTo(2301);

        // 草稿文章：游客读其评论区 2001（有评论会泄露草稿存在性——不可见即不存在）
        Map<String, Object> env = call("POST", "/api/articles", user, Map.of(
                "title", "M5草稿载体", "contentMd", "草稿正文。", "status", 0));
        long draft = ((Number) castMap(env.get("data")).get("id")).longValue();
        assertThat((Integer) call("GET", "/api/comments?articleId=" + draft, null, null).get("code")).isEqualTo(2001);
        // 匿名评草稿在 Security 层就被 1001 挡（POST 不在白名单）
        assertThat((Integer) call("POST", "/api/comments", null,
                Map.of("articleId", draft, "content", "偷评草稿")).get("code")).isEqualTo(1001);
        // 作者给自己草稿留评论放行（自用备注场景；发布后对公众可见）
        assertThat((Integer) call("POST", "/api/comments", user,
                Map.of("articleId", draft, "content", "给自己草稿留个记号")).get("code")).isZero();
    }

    // ==================== 审核开关（提交→审核→展示 全流程） ====================

    @Test
    @DisplayName("先审后发：pending 不回对象不进计数；通过→可见+1 且幂等；屏蔽→-1；屏蔽顶级连带回复；幽灵 2303")
    void auditFlow() throws Exception {
        // 打开审核开关（类级 @Transactional 回滚，不污染其它用例与别的测试类）
        jdbcTemplate.update("UPDATE sys_config SET config_value = 'true' WHERE config_key = 'comment_audit'");

        String user = loginForToken("testuser");
        String admin = loginForToken("other");
        long art = publishArticle(user, "-audit", null);

        // 1. 提交 → pending：无评论对象、不进计数、公开列表不可见
        Map<String, Object> env = call("POST", "/api/comments", user,
                Map.of("articleId", art, "content", "等待审核的评论"));
        assertThat((Integer) env.get("code")).isZero();
        assertThat((String) env.get("message")).isEqualTo("评论已提交，等待审核");
        Map<String, Object> data = castMap(env.get("data"));
        assertThat((Boolean) data.get("pending")).isTrue();
        assertThat(data.get("comment")).as("审核期不回评论对象（文档 §6）").isNull();
        assertThat(publicComments(art)).isEmpty();
        assertThat(commentCountOf(art, admin)).as("待审不进计数").isEqualTo(0);

        // 2. 待审队列：管理端 ?status=0 能查到，且带文章标题（审核员语境）
        List<Map<String, Object>> queue = castList(castMap(
                call("GET", "/api/admin/comments?status=0", admin, null).get("data")).get("list"));
        Map<String, Object> pendingVo = queue.stream()
                .filter(c -> "等待审核的评论".equals(c.get("content"))).findFirst().orElseThrow();
        long pid = ((Number) pendingVo.get("id")).longValue();
        assertThat((String) pendingVo.get("articleTitle")).startsWith("M5评论载体文章-audit");

        // 3. 通过 → 公开可见 +1；重复通过幂等不加计数
        Map<String, Object> approve = call("PATCH", "/api/admin/comments/" + pid + "/audit", admin,
                Map.of("status", 1));
        assertThat((Integer) approve.get("code")).isZero();
        assertThat((String) approve.get("message")).isEqualTo("已通过");
        assertThat(publicComments(art)).hasSize(1);
        assertThat(commentCountOf(art, admin)).isEqualTo(1);
        call("PATCH", "/api/admin/comments/" + pid + "/audit", admin, Map.of("status", 1));
        assertThat(commentCountOf(art, admin)).as("幂等：重复通过不重复计数").isEqualTo(1);
        ageComment(pid, 60); // 释放该用户在此文章上的刷屏闸门，供后续步骤复用身份

        // 4. 屏蔽 → 公开消失、计数回落
        call("PATCH", "/api/admin/comments/" + pid + "/audit", admin, Map.of("status", 2));
        assertThat(publicComments(art)).isEmpty();
        assertThat(commentCountOf(art, admin)).isEqualTo(0);

        // 5. 屏蔽顶级连带屏蔽已通过回复（断头对话防御）
        jdbcTemplate.update("UPDATE sys_config SET config_value = 'false' WHERE config_key = 'comment_audit'");
        long topId = idOfCommentVO(comment(admin, art, "要被屏蔽的顶级"));
        ageComment(topId, 60);
        long repId = idOfCommentVO(callData(call("POST", "/api/comments", user, Map.of(
                "articleId", art, "parentId", topId, "content", "通过的回复"))));
        assertThat(commentCountOf(art, admin)).as("顶级+回复可见时计数=2").isEqualTo(2);
        call("PATCH", "/api/admin/comments/" + topId + "/audit", admin, Map.of("status", 2));
        assertThat(commentCountOf(art, admin)).as("屏蔽顶级连带回复各-1").isEqualTo(0);
        assertThat(publicComments(art)).isEmpty();
        Integer repStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM comment WHERE id = ?", Integer.class, repId);
        assertThat(repStatus).as("回复被连带置为屏蔽态").isEqualTo(2);

        // 6. 幽灵审核 → 2303 + 404；非法审核值 0（退回待审不在语义域）→ 400
        mockMvc.perform(patch("/api/admin/comments/987654/audit")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":1}"))
                .andExpect(status().isNotFound())
                .andExpect(r -> assertThat((Integer) readEnvelope(r).get("code")).isEqualTo(2303));
        assertThat((Integer) call("PATCH", "/api/admin/comments/" + pid + "/audit", admin,
                Map.of("status", 0)).get("code")).isEqualTo(400);
    }

    /** 从"发表成功"信封里取 data（call 返回的是完整信封） */
    private Map<String, Object> callData(Map<String, Object> envelope) {
        assertThat((Integer) envelope.get("code")).as("请求应成功: %s", envelope.get("message")).isZero();
        return castMap(envelope.get("data"));
    }

    // ==================== 删除与级联 ====================

    @Test
    @DisplayName("删除：本人删回复计数-1；他人删 1005；ADMIN 删他人顶级级联+计数-1；幽灵 2303")
    void deleteCascade() throws Exception {
        String user = loginForToken("testuser");
        String admin = loginForToken("other");
        String[] third = registerFreshUser();
        long art = publishArticle(user, "-del", null);

        long topId = idOfCommentVO(comment(admin, art, "ADMIN的顶级"));
        ageComment(topId, 60);
        long repId = idOfCommentVO(callData(call("POST", "/api/comments", user, Map.of(
                "articleId", art, "parentId", topId, "content", "我的回复"))));
        assertThat(commentCountOf(art, user)).isEqualTo(2);

        // 第三方既非属主也非 ADMIN → 1005（先判存在再比属主，2303 与 1005 不混）
        assertThat((Integer) call("DELETE", "/api/comments/" + repId, third[0], null).get("code")).isEqualTo(1005);

        // 本人删自己的回复：计数 -1，顶级 replies 变空
        assertThat((Integer) call("DELETE", "/api/comments/" + repId, user, null).get("code")).isZero();
        assertThat(commentCountOf(art, user)).isEqualTo(1);
        assertThat(castList(publicComments(art).get(0).get("replies"))).as("回复已从嵌套里消失").isEmpty();

        // ADMIN 删他人评论放行；顶级与其（已被删的）子回复级联语义由 deleted 列钉死
        long userComment = idOfCommentVO(comment(user, art, "给用户自己删不动的"));
        assertThat((Integer) call("DELETE", "/api/comments/" + userComment, admin, null).get("code")).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT deleted FROM comment WHERE id = ?", Integer.class, userComment))
                .as("逻辑删除：行还在 deleted=1").isEqualTo(1);

        // ADMIN 删自己的顶级（其下已无存活回复）：计数回到 0
        assertThat((Integer) call("DELETE", "/api/comments/" + topId, admin, null).get("code")).isZero();
        assertThat(commentCountOf(art, user)).isEqualTo(0);
        assertThat(publicComments(art)).isEmpty();

        // 顶级的级联删除：再造一条顶级+回复，删顶级后回复 deleted=1
        long top2 = idOfCommentVO(comment(admin, art, "级联顶级"));
        ageComment(top2, 60);
        long rep2 = idOfCommentVO(callData(call("POST", "/api/comments", user, Map.of(
                "articleId", art, "parentId", top2, "content", "级联回复"))));
        ageComment(rep2, 60);
        assertThat((Integer) call("DELETE", "/api/comments/" + top2, admin, null).get("code")).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT deleted FROM comment WHERE id = ?", Integer.class, rep2))
                .as("删顶级必须级联删回复（断头对话不留）").isEqualTo(1);
        assertThat(commentCountOf(art, user)).as("级联删 2 条通过态评论，计数一次回退").isEqualTo(0);

        // 幽灵删除 → 2303 + 404
        mockMvc.perform(delete("/api/comments/987654")
                        .header("Authorization", "Bearer " + user))
                .andExpect(status().isNotFound())
                .andExpect(r -> assertThat((Integer) readEnvelope(r).get("code")).isEqualTo(2303));
    }

    // ==================== 禁言 / 刷屏 / XSS ====================

    @Test
    @DisplayName("安全：令牌签发后被封号→发言 1003；10s 连发 400；HTML 载荷清洗留文字；纯标签/超长 400")
    void safetyGates() throws Exception {
        String user = loginForToken("testuser");
        long art = publishArticle(user, "-sec", null);

        // 1. 禁言：新号拿到有效令牌 → 库里封号 → 存量令牌发言被拒
        //    （JWT 无状态方案的盲区在写入口回查 sys_user.status，需求分析规则 3）
        String[] mute = registerFreshUser();
        jdbcTemplate.update("UPDATE sys_user SET status = 0 WHERE id = ?", Long.parseLong(mute[1]));
        assertThat((Integer) call("POST", "/api/comments", mute[0],
                Map.of("articleId", art, "content", "被禁言还想说话")).get("code")).isEqualTo(1003);

        // 2. 防刷屏：同人同文章 10s 内第二条 400；把先行时间拨回后即放行
        long first = idOfCommentVO(comment(user, art, "第一条"));
        assertThat((Integer) call("POST", "/api/comments", user,
                Map.of("articleId", art, "content", "秒发第二条")).get("code")).isEqualTo(400);
        ageComment(first, 60);
        long second = idOfCommentVO(comment(user, art, "隔了再发第二条"));

        // 3. XSS：混写 HTML 的评论——标签/事件属性剥光、可见文字保留（前端 {{content}}
        //    文本插值是第二道闸，双防线口径）
        ageComment(second, 60);
        Map<String, Object> xssVo = castMap(comment(user, art,
                "<script>alert(1)</script>这段文字要留下<img src=x onerror=alert(2)>").get("comment"));
        String content = (String) xssVo.get("content");
        assertThat(content).doesNotContain("<").doesNotContain("script").doesNotContain("onerror")
                .contains("这段文字要留下");

        // 4. 纯 HTML 载荷（清洗后为空）→ 400 明确拒绝，绝不静默存空串
        ageComment(((Number) xssVo.get("id")).longValue(), 60);
        assertThat((Integer) call("POST", "/api/comments", user,
                Map.of("articleId", art, "content", "<b></b><i></i>")).get("code")).isEqualTo(400);
        // 5. 超 1000 字 → DTO @Size 直拒
        assertThat((Integer) call("POST", "/api/comments", user, Map.of(
                "articleId", art, "content", "长".repeat(1001))).get("code")).isEqualTo(400);
    }

    // ==================== 我的评论（UC-U-12） ====================

    @Test
    @DisplayName("mine=1：本人可见全部状态（含待审）；他人评论不混入；游客 1001")
    void myCommentsVisibleAllStatus() throws Exception {
        String user = loginForToken("testuser");
        String admin = loginForToken("other");
        long art = publishArticle(user, "-mine", null);

        // 待审一条 + ADMIN 直发一条再屏蔽：mine 列表只含"自己的"，状态对自己透明
        jdbcTemplate.update("UPDATE sys_config SET config_value = 'true' WHERE config_key = 'comment_audit'");
        call("POST", "/api/comments", user, Map.of("articleId", art, "content", "我的待审评论"));
        jdbcTemplate.update("UPDATE sys_config SET config_value = 'false' WHERE config_key = 'comment_audit'");
        long blockId = idOfCommentVO(comment(admin, art, "ADMIN评论将被屏蔽"));
        call("PATCH", "/api/admin/comments/" + blockId + "/audit", admin, Map.of("status", 2));

        List<Map<String, Object>> mine = castList(castMap(
                call("GET", "/api/comments?mine=1&pageSize=100", user, null).get("data")).get("list"));
        // 共享库有历史评论：按本轮文章 id 过滤后断言（M3 台账"包含关系"纪律）
        List<Map<String, Object>> mineOnArt = mine.stream()
                .filter(c -> ((Number) c.get("articleId")).longValue() == art).toList();
        assertThat(mineOnArt).extracting(c -> (String) c.get("content"))
                .containsExactly("我的待审评论");
        assertThat((Integer) mineOnArt.get(0).get("status")).as("待审状态对自己透明").isEqualTo(0);
        assertThat(mineOnArt).extracting(c -> (String) c.get("content"))
                .doesNotContain("ADMIN评论将被屏蔽");
    }
}
