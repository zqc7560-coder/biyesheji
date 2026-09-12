package com.blog.module.system;

import com.blog.module.article.service.ArticleCacheService;
import com.blog.module.system.service.SysConfigService;
import com.blog.job.PvSyncJob;
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

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * M9 缓存与性能模块接口级测试 —— 覆盖《数据库设计.md》§4 键空间的四条 blog/sys 键族
 * 与《系统设计.md》§6 决策 3/4 的失效与回写纪律。
 *
 * <p>测试策略（与 M2~M8 同一套基建）：InMemoryRedis @Primary 顶掉真 Redis、类级
 * @Transactional 回滚 DB、TestCacheGuardExtension（自动探测）在每个用例前清空共享
 * 内存 Redis——用例从"缓存空态"起跑，缓存行为完全由本类显式驱动与断言。
 *
 * <p>断言哲学：缓存的正确性不在"快"，在"脏窗口有多长、谁来关门"。所以每个用例都
 * 钉的是同一形状：<b>写路径是否真的关掉了读路径留下的门（缓存键）</b>——
 * 更新/流转/删除/评论计数/标签分类治理逐一验证，再验证应急口（清缓存/补偿队列）。
 *
 * <p>身份物料：testuser(id=1, ROLE_USER)、other(id=2, ROLE_ADMIN 有 config:manage)，
 * 口令 password123；种子分类 id=1 技术。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@DisplayName("M9 缓存体系: 详情/列表缓存与失效/PV计数回写/sys:config镜像/缓存运维口")
class CacheApiM9Test {

    private static final String SEED_PWD = "password123";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private StringRedisTemplate redis;
    /** 被测组件直调（同步语义：测试禁用 @Scheduled 自动触发，见 application-test.yml） */
    @Autowired
    private PvSyncJob pvSyncJob;
    @Autowired
    private SysConfigService sysConfigService;
    @Autowired
    private ArticleCacheService articleCache;

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

    /** 读信封：显式 UTF-8 解字节（MockMvc ISO-8859-1 坑，台账 M2 记录） */
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

    /** 直接发布一篇文章（status=1），返回文章 id —— 缓存用例的公共前置 */
    private long publishArticle(String token, String title) throws Exception {
        Map<String, Object> env = readEnvelope(mockMvc.perform(post("/api/articles")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "title", title,
                                "categoryId", 1,
                                "tags", List.of("缓存主题"),
                                "contentMd", "# 缓存验证文\n\n这是用于验证 M9 缓存链路"
                                        + "正文与失效纪律的测试文章，字数需超过最小门槛。",
                                "status", 1,
                                "allowComment", 1))))
                .andReturn());
        assertThat((Integer) env.get("code")).as("发布应成功: %s", env.get("message")).isZero();
        return ((Number) castMap(env.get("data")).get("id")).longValue();
    }

    private boolean hasKey(String k) {
        return Boolean.TRUE.equals(redis.hasKey(k));
    }

    // ==================== 详情缓存（blog:article:{id}） ====================

    @Test
    @DisplayName("详情缓存：miss回源回填 → 命中跳过DB视图；编辑后失效，再读见新值")
    void detailCacheHitAndEvictOnEdit() throws Exception {
        String owner = loginForToken("testuser");
        long id = publishArticle(owner, "M9 详情缓存样本");

        // 第一读：miss → 回源 → 回填（守卫保证用例起点缓存为空，此处必为回源路径）
        mockMvc.perform(get("/api/articles/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.title").value("M9 详情缓存样本"));
        assertThat(hasKey(ArticleCacheService.DETAIL_PREFIX + id))
                .as("发布态详情读后应回填 blog:article:{id}").isTrue();

        // 绕过服务层直改 DB（模拟"缓存未失效时的世界"）：命中路径应读到旧标题——
        // 钉的是"命中即读缓存"这条分支真的存在，而不是每次都回源
        jdbcTemplate.update("update article set title='旁路改脏值' where id=?", id);
        mockMvc.perform(get("/api/articles/" + id))
                .andExpect(jsonPath("$.data.title").value("M9 详情缓存样本"));

        // 正规写路径（PUT 更新）→ evictTx 双删 → 再读必须见新值
        mockMvc.perform(put("/api/articles/" + id)
                        .header("Authorization", "Bearer " + owner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "title", "M9 详情缓存样本（改）",
                                "categoryId", 1,
                                "contentMd", "# 改后\n\n正文也换掉，验证 VO 全字段跟着新。",
                                "status", 1,
                                "version", 0))))
                .andExpect(status().isOk());
        assertThat(hasKey(ArticleCacheService.DETAIL_PREFIX + id)).isFalse();
        mockMvc.perform(get("/api/articles/" + id))
                .andExpect(jsonPath("$.data.title").value("M9 详情缓存样本（改）"));
    }

    @Test
    @DisplayName("草稿/下线文章不入缓存、不见缓存；下线动作必须让已缓存的公开视图即刻消失")
    void draftNeverCachedOfflineEvicted() throws Exception {
        String owner = loginForToken("testuser");
        // 草稿：作者读详情后键必须缺席（缓存只装"人人一致"的公开视图）
        Map<String, Object> draftEnv = readEnvelope(mockMvc.perform(post("/api/articles")
                .header("Authorization", "Bearer " + owner)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                        "title", "M9 草稿不该进缓存",
                        "categoryId", 1,
                        "contentMd", "草稿正文，验证缓存入池闸门只认发布态，这一样要够长。",
                        "status", 0))))
                .andReturn());
        long draftId = ((Number) castMap(draftEnv.get("data")).get("id")).longValue();
        mockMvc.perform(get("/api/articles/" + draftId)
                        .header("Authorization", "Bearer " + owner))
                .andExpect(status().isOk());
        assertThat(hasKey(ArticleCacheService.DETAIL_PREFIX + draftId))
                .as("草稿读后不得回填缓存（可见性矩阵与身份相关）").isFalse();

        // 发布 → 缓存；再下线 → 键消失且游客 404（治理动作的执法闭环）
        long id = publishArticle(owner, "M9 下线清缓存样本");
        mockMvc.perform(get("/api/articles/" + id)).andExpect(status().isOk());
        assertThat(hasKey(ArticleCacheService.DETAIL_PREFIX + id)).isTrue();
        mockMvc.perform(patch("/api/articles/" + id + "/status")
                        .header("Authorization", "Bearer " + owner)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":2}"))
                .andExpect(status().isOk());
        assertThat(hasKey(ArticleCacheService.DETAIL_PREFIX + id))
                .as("下线必须同步删详情缓存").isFalse();
        mockMvc.perform(get("/api/articles/" + id))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(2001));
    }

    @Test
    @DisplayName("删除文章 → 详情缓存消失（否则 30min 内游客仍能点到已删正文）")
    void deleteEvictsDetail() throws Exception {
        String owner = loginForToken("testuser");
        long id = publishArticle(owner, "M9 删除清缓存样本");
        mockMvc.perform(get("/api/articles/" + id)).andExpect(status().isOk());
        assertThat(hasKey(ArticleCacheService.DETAIL_PREFIX + id)).isTrue();

        mockMvc.perform(delete("/api/articles/" + id)
                        .header("Authorization", "Bearer " + owner))
                .andExpect(status().isOk());
        assertThat(hasKey(ArticleCacheService.DETAIL_PREFIX + id)).isFalse();
    }

    // ==================== 列表缓存（blog:list:{md5} + blog:listkeys） ====================

    @Test
    @DisplayName("公开列表回填+登记 listkeys；新发布触发全列表键失效；再读见新文章")
    void publicListCacheAndEvict() throws Exception {
        // 空态起步（守卫已清）：读一次公开列表 → 建键并登记索引
        mockMvc.perform(get("/api/articles")).andExpect(status().isOk());
        Set<String> indexed = redis.opsForSet().members(ArticleCacheService.LIST_INDEX);
        assertThat(indexed).as("列表键应登记进 blog:listkeys").hasSize(1);
        String listKey = indexed.iterator().next();
        assertThat(listKey).startsWith(ArticleCacheService.LIST_PREFIX);
        assertThat(hasKey(listKey)).isTrue();

        // 新发布 → create() 失效 → 登记索引整体作废
        String owner = loginForToken("testuser");
        publishArticle(owner, "M9 列表新帖可见性样本");
        assertThat(hasKey(listKey)).isFalse();
        Set<String> afterEvict = redis.opsForSet().members(ArticleCacheService.LIST_INDEX);
        assertThat(afterEvict == null ? Set.of() : afterEvict).isEmpty();

        // 再读：回源新值，列表里出现刚发布的文章
        Map<String, Object> env = readEnvelope(mockMvc.perform(get("/api/articles"))
                .andReturn());
        List<Map<String, Object>> rows =
                (List<Map<String, Object>>) castMap(env.get("data")).get("list");
        assertThat(rows).extracting(r -> (String) r.get("title"))
                .contains("M9 列表新帖可见性样本");
    }

    @Test
    @DisplayName("mine=1 / adminScope 视图不进缓存（身份维度视图混缓存=越权或炸键）")
    void identityViewsBypassCache() throws Exception {
        String owner = loginForToken("testuser");
        String admin = loginForToken("other");
        mockMvc.perform(get("/api/articles").param("mine", "1")
                        .header("Authorization", "Bearer " + owner))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/articles").param("status", "0")
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk());
        // 两个身份视图各读一次后：列表键族与索引都必须空
        assertThat(redis.keys(ArticleCacheService.LIST_PREFIX + "*")).isEmpty();
        Set<String> indexed = redis.opsForSet().members(ArticleCacheService.LIST_INDEX);
        assertThat(indexed == null ? Set.of() : indexed).isEmpty();
    }

    @Test
    @DisplayName("空结果同样缓存：不存在的关键词被反复搜，成本封在 Redis 里")
    void emptyPageIsCached() throws Exception {
        mockMvc.perform(get("/api/articles").param("keyword", "zz不存在的检索词zz"))
                .andExpect(status().isOk());
        assertThat(redis.opsForSet().members(ArticleCacheService.LIST_INDEX))
                .as("空页也登记回填（防搜索穿透）").hasSize(1);
    }

    // ==================== PV 计数与定时回写（blog:pv:{id} → view_count） ====================

    @Test
    @DisplayName("详情读 INCR blog:pv/blog:pvday；syncNow 把增量加回 view_count 并清计数键")
    void pvCountAndSyncWriteBack() throws Exception {
        String owner = loginForToken("testuser");
        long id = publishArticle(owner, "M9 PV回写样本");

        // 两次浏览（一次回源+一次命中，两条路径都必须计数）
        mockMvc.perform(get("/api/articles/" + id)).andExpect(status().isOk());
        mockMvc.perform(get("/api/articles/" + id)).andExpect(status().isOk());
        assertThat(redis.opsForValue().get(ArticleCacheService.PV_PREFIX + id))
                .as("两次详情读应累计 2 笔 PV").isEqualTo("2");
        String today = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
        assertThat(redis.opsForValue().get(ArticleCacheService.PVDAY_PREFIX + today))
                .isEqualTo("2");

        // 404 不计数：看不见的东西被"浏览"了会污染趋势（先验证闸门再回写）
        mockMvc.perform(get("/api/articles/9999999")).andExpect(status().isNotFound());
        assertThat(redis.opsForValue().get(ArticleCacheService.PVDAY_PREFIX + today))
                .isEqualTo("2");

        PvSyncJob.Result r = pvSyncJob.syncNow();
        assertThat(r.applied()).isEqualTo(1);
        assertThat(r.totalDelta()).isEqualTo(2L);

        // DB 事实：view_count 落 2；计数键被取走；按日键留存（趋势数据源不许归零）
        Long viewCount = jdbcTemplate.queryForObject(
                "select view_count from article where id=?", Long.class, id);
        assertThat(viewCount).isEqualTo(2L);
        assertThat(hasKey(ArticleCacheService.PV_PREFIX + id)).isFalse();
        assertThat(hasKey(ArticleCacheService.PVDAY_PREFIX + today)).isTrue();

        // 幂等：再跑一轮没有增量可回写
        assertThat(pvSyncJob.syncNow().applied()).isZero();
    }

    @Test
    @DisplayName("失效补偿队列：手工入队后 syncNow 排空（R-04 DEL 失败的事后修复）")
    void evictRetryDrain() throws Exception {
        String owner = loginForToken("testuser");
        long id = publishArticle(owner, "M9 补偿队列样本");
        mockMvc.perform(get("/api/articles/" + id)).andExpect(status().isOk());

        redis.opsForSet().add(ArticleCacheService.EVICT_RETRY, String.valueOf(id));
        PvSyncJob.Result r = pvSyncJob.syncNow();
        assertThat(r.recovered()).isGreaterThanOrEqualTo(1L);
        // 排空 + 详情键确实被补偿删掉
        Set<String> left = redis.opsForSet().members(ArticleCacheService.EVICT_RETRY);
        assertThat(left == null ? Set.of() : left).doesNotContain(String.valueOf(id));
        assertThat(hasKey(ArticleCacheService.DETAIL_PREFIX + id)).isFalse();
    }

    // ==================== 跨模块失效接线（评论计数 / 标签 / 分类治理） ====================

    @Test
    @DisplayName("评论通过态结算 comment_count → 详情缓存同步失效（M5 写链与 M9 闭环）")
    void commentCountEvictsDetail() throws Exception {
        String owner = loginForToken("testuser");
        String commenter = loginForToken("other");
        long id = publishArticle(owner, "M9 评论联动样本");
        mockMvc.perform(get("/api/articles/" + id)).andExpect(status().isOk());
        assertThat(hasKey(ArticleCacheService.DETAIL_PREFIX + id)).isTrue();

        // 种子 comment_audit=false → 直发即通过 → bumpCommentCount(+1) → 必须失效
        mockMvc.perform(post("/api/comments")
                        .header("Authorization", "Bearer " + commenter)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "articleId", id, "content", "M9 联动验证评论一条"))))
                .andExpect(status().isOk());
        assertThat(hasKey(ArticleCacheService.DETAIL_PREFIX + id))
                .as("comment_count 变动必须让详情缓存关账").isFalse();
        mockMvc.perform(get("/api/articles/" + id))
                .andExpect(jsonPath("$.data.commentCount").value(1));
    }

    @Test
    @DisplayName("标签改名/分类改名（治理口）→ evictAll：波及面不可枚举的变更走全量失效")
    void governanceRenamesEvictAll() throws Exception {
        String admin = loginForToken("other");
        String owner = loginForToken("testuser");
        long id = publishArticle(owner, "M9 治理失效样本");
        mockMvc.perform(get("/api/articles/" + id)).andExpect(status().isOk());
        mockMvc.perform(get("/api/articles")).andExpect(status().isOk());
        assertThat(hasKey(ArticleCacheService.DETAIL_PREFIX + id)).isTrue();

        // 分类改名（种子 id=1 技术 → 科技）：详情+列表键都要消失
        mockMvc.perform(put("/api/categories/1")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", "科技", "parentId", 0))))
                .andExpect(status().isOk());
        assertThat(hasKey(ArticleCacheService.DETAIL_PREFIX + id)).isFalse();
        assertThat(redis.keys(ArticleCacheService.LIST_PREFIX + "*")).isEmpty();

        // 标签改名同理（先拿草稿用的标签 id 再改）
        Long tagId = jdbcTemplate.queryForObject(
                "select id from tag where name='缓存主题' order by id desc limit 1", Long.class);
        long id2 = publishArticle(owner, "M9 治理失效样本二");
        mockMvc.perform(get("/api/articles/" + id2)).andExpect(status().isOk());
        mockMvc.perform(put("/api/tags/" + tagId)
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("name", "缓存专题"))))
                .andExpect(status().isOk());
        assertThat(hasKey(ArticleCacheService.DETAIL_PREFIX + id2)).isFalse();
    }

    // ==================== sys:config 镜像（启动加载/双删/事务脏回填闸门） ====================

    @Test
    @DisplayName("镜像 serving 读：reloadMirror 后公开口出镜像值；管理端 PUT 双删后回 DB 真相")
    void sysConfigMirrorServingAndDoubleDelete() throws Exception {
        String admin = loginForToken("other");

        // 全量灌镜像（启动引导器的同一入口，测试里显式驱动——ApplicationRunner
        // 在 @SpringBootTest 下不会自动执行）
        long rows = sysConfigService.reloadMirror();
        assertThat(rows).isGreaterThanOrEqualTo(7L);   // 种子参数行数
        // Hash 型键用 entries 判在位（内存版 hasKey 只认 String 分身，与真 Redis 语义一致）
        assertThat(redis.opsForHash().entries(SysConfigService.MIRROR_KEY))
                .containsKey("__loaded_at");

        // 旁路改 DB（绕过 Service 的失效链）：公开口仍读镜像旧值——钉死"镜像是读源"
        jdbcTemplate.update("update sys_config set config_value='旁路脏值' where config_key='site_name'");
        Map<String, Object> pub = castMap(readEnvelope(mockMvc.perform(get("/api/system/config"))
                .andReturn()).get("data"));
        assertThat(pub.get("site_name")).as("镜像存在时读镜像，不读被旁路改过的库")
                .isEqualTo("我的博客");

        // 正规修改口：PUT 成功 → 第一删立即生效（镜像消失）
        mockMvc.perform(put("/api/admin/system/config")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("items", List.of(
                                Map.of("key", "site_name", "value", "M9 镜像后新站名"))))))
                .andExpect(status().isOk());
        assertThat(redis.opsForHash().entries(SysConfigService.MIRROR_KEY))
                .as("修改口写前 DEL（双删第一删）——镜像整键消失").isEmpty();

        // 镜像消失后公开口回 DB 真相；且测试事务内读不回填镜像（脏回填闸门）
        Map<String, Object> pub2 = castMap(readEnvelope(mockMvc.perform(get("/api/system/config"))
                .andReturn()).get("data"));
        assertThat(pub2.get("site_name")).isEqualTo("M9 镜像后新站名");
        assertThat(redis.opsForHash().entries(SysConfigService.MIRROR_KEY))
                .as("事务上下文内 publicConfig 不得回填镜像（会把未提交值灌给所有读者）").isEmpty();
    }

    // ==================== 仪表盘 PV 真实化 + 缓存运维口 ====================

    @Test
    @DisplayName("dashboard pvTrend 出真实 pvday 序列、totalPv 含未回写增量（M8 占位兑现）")
    void dashboardPvRealSeries() throws Exception {
        String owner = loginForToken("testuser");
        String admin = loginForToken("other");
        long id = publishArticle(owner, "M9 看板PV样本");
        mockMvc.perform(get("/api/articles/" + id)).andExpect(status().isOk());
        mockMvc.perform(get("/api/articles/" + id)).andExpect(status().isOk());

        Map<String, Object> data = castMap(readEnvelope(mockMvc.perform(
                get("/api/admin/dashboard").header("Authorization", "Bearer " + admin))
                .andReturn()).get("data"));
        List<Map<String, Object>> pvTrend =
                (List<Map<String, Object>>) data.get("pvTrend");
        assertThat(pvTrend).hasSize(7);
        // 末位=今天，值=2（守卫保证无历史污染，绝对值可断言）
        Map<String, Object> todayPoint = pvTrend.get(6);
        assertThat(todayPoint.get("date")).isEqualTo(LocalDate.now().toString());
        assertThat(((Number) todayPoint.get("value")).longValue()).isEqualTo(2L);
        // totalPv=存量+未回写增量：syncNow 前回写增量在 Redis 里，必须计入实时口径
        assertThat(((Number) data.get("totalPv")).longValue()).isGreaterThanOrEqualTo(2L);
        assertThat((String) data.get("note")).contains("pvday");
    }

    @Test
    @DisplayName("缓存运维口：RBAC 三态 + stats 键族契约 + 一键清空只动展示缓存不误伤 PV")
    void cacheOpsEndpoints() throws Exception {
        // RBAC：匿名 401 / 普通用户 403
        mockMvc.perform(get("/api/admin/cache/stats")).andExpect(status().isUnauthorized());
        String userToken = loginForToken("testuser");
        mockMvc.perform(get("/api/admin/cache/stats")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden());

        String admin = loginForToken("other");
        long id = publishArticle(userToken, "M9 运维口样本");
        mockMvc.perform(get("/api/articles/" + id)).andExpect(status().isOk());  // 建详情键+PV键
        mockMvc.perform(get("/api/articles")).andExpect(status().isOk());        // 建列表键

        Map<String, Object> stats = castMap(readEnvelope(mockMvc.perform(
                get("/api/admin/cache/stats").header("Authorization", "Bearer " + admin))
                .andReturn()).get("data"));
        assertThat(stats).containsKeys("detailKeys", "listKeys", "listIndexMembers",
                "pvKeys", "pvDayKeys", "evictRetryPending");
        assertThat(((Number) stats.get("detailKeys")).longValue()).isGreaterThanOrEqualTo(1L);

        // 一键清空：展示缓存归零，PV 计数器不误伤（清一次=浏览量白记）
        Map<String, Object> cleared = castMap(readEnvelope(mockMvc.perform(
                delete("/api/admin/cache").header("Authorization", "Bearer " + admin))
                .andReturn()).get("data"));
        assertThat(((Number) cleared.get("deleted")).longValue()).isGreaterThanOrEqualTo(2L);
        assertThat(hasKey(ArticleCacheService.DETAIL_PREFIX + id)).isFalse();
        assertThat(redis.keys(ArticleCacheService.LIST_PREFIX + "*")).isEmpty();
        assertThat(hasKey(ArticleCacheService.PV_PREFIX + id))
                .as("清展示缓存不许吞掉未回写的浏览量").isTrue();
    }

    @Test
    @DisplayName("手动回写触发口：POST /cache/pv-sync 落账+幂等重触发（RBAC 同口径）")
    void manualPvSyncEndpoint() throws Exception {
        String owner = loginForToken("testuser");
        long id = publishArticle(owner, "M9 手动回写样本");
        mockMvc.perform(get("/api/articles/" + id)).andExpect(status().isOk());

        // 普通用户 403（config:manage 类级口径对新口自动生效）
        mockMvc.perform(post("/api/admin/cache/pv-sync")
                        .header("Authorization", "Bearer " + owner))
                .andExpect(status().isForbidden());

        String admin = loginForToken("other");
        Map<String, Object> data = castMap(readEnvelope(mockMvc.perform(
                post("/api/admin/cache/pv-sync").header("Authorization", "Bearer " + admin))
                .andReturn()).get("data"));
        assertThat(((Number) data.get("applied")).longValue()).isEqualTo(1L);
        assertThat(((Number) data.get("totalDelta")).longValue()).isEqualTo(1L);
        // 落库事实 + 计数键已取走
        assertThat(jdbcTemplate.queryForObject(
                "select view_count from article where id=?", Long.class, id)).isEqualTo(1L);
        assertThat(hasKey(ArticleCacheService.PV_PREFIX + id)).isFalse();
        // 幂等：再触发一轮没有增量（重复点击不双计）
        Map<String, Object> again = castMap(readEnvelope(mockMvc.perform(
                post("/api/admin/cache/pv-sync").header("Authorization", "Bearer " + admin))
                .andReturn()).get("data"));
        assertThat(((Number) again.get("applied")).longValue()).isZero();
    }

    // ==================== 降级面（缓存是加速器不是正确性依赖） ====================

    @Test
    @DisplayName("Redis 读异常折叠为未命中：详情/列表照常回源（缓存故障不许把请求炸成 500）")
    void redisFailureDegradesToSource() throws Exception {
        String owner = loginForToken("testuser");
        long id = publishArticle(owner, "M9 降级样本");

        // 往详情键灌一份"不可反序列化"的垃圾载荷：getDetail 解析失败 → 按未命中处理
        redis.opsForValue().set(ArticleCacheService.DETAIL_PREFIX + id, "{ 这不是合法JSON");
        mockMvc.perform(get("/api/articles/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.title").value("M9 降级样本"));

        // 列表键同理：垃圾值不阻断回源，且回源后键被健康值覆盖
        String listKey = articleCache.listKey("p=1&s=10&cat=-&tag=-&kw=-&sort=-");
        redis.opsForValue().set(listKey, "corrupted!!");
        mockMvc.perform(get("/api/articles"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }
}
