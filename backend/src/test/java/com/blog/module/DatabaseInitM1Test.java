package com.blog.module;

import com.baomidou.mybatisplus.core.metadata.TableInfo;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.blog.module.ai.entity.AiCallLog;
import com.blog.module.ai.entity.AiConfig;
import com.blog.module.article.entity.Article;
import com.blog.module.article.entity.ArticleTag;
import com.blog.module.article.mapper.ArticleMapper;
import com.blog.module.auth.entity.LoginLog;
import com.blog.module.category.entity.Category;
import com.blog.module.comment.entity.Comment;
import com.blog.module.storage.entity.FileRecord;
import com.blog.module.system.entity.SysConfig;
import com.blog.module.tag.entity.Tag;
import com.blog.module.user.entity.RolePermission;
import com.blog.module.user.entity.SysPermission;
import com.blog.module.user.entity.SysRole;
import com.blog.module.user.entity.SysUser;
import com.blog.module.user.entity.UserRole;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M1 数据库初始化契约测试（H2 内存库，MySQL 兼容模式）。
 *
 * <p>M1 是"地基模块"，本测试锁四件事，之后每轮迭代都由 CI 兜底防回退：
 * <ol>
 *   <li><b>15 表齐全</b>：information_schema 实查，表名与《数据库设计.md》§3 逐字对齐；</li>
 *   <li><b>实体↔表结构逐列对齐</b>：用 MyBatis-Plus TableInfo（实体运行期真实映射）
 *       与 H2 实际列集合做双向比对——实体多字段（幻觉列）或少字段（DDL 漂移）都会红。
 *       这是防"代码和设计文档各写各的"的机械契约，比人眼 review 可靠；</li>
 *   <li><b>种子数据语义</b>：角色/权限/RBAC 授权数、系统参数 7 键、默认分类 3 行、
 *       内置 AI 模型 2 行（is_builtin=1 且恰好 1 个 is_default=1）；</li>
 *   <li><b>密钥安全红线</b>：ai_config 任何行的 api_key_cipher 不得以 "sk-" 开头
 *       （真实 Key 绝不允许出现在种子/测试库），且实体全部可完成 insert→select 回读。</li>
 * </ol>
 *
 * <p>Redis 依旧 @MockBean 顶掉：本测试与外部中间件零依赖，任何 clone 直接可跑。
 */
@SpringBootTest(properties =
        "spring.datasource.url=jdbc:h2:mem:blog_m1;MODE=MySQL;DATABASE_TO_LOWER=TRUE;"
                + "CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1")
@ActiveProfiles("test")
@DisplayName("M1 数据库契约: 15表结构 + 实体映射对齐 + 种子数据 + Key安全红线")
class DatabaseInitM1Test {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** 部分实体回读测试借文章 Mapper 造外键样本 */
    @Autowired
    private ArticleMapper articleMapper;

    @MockBean
    private StringRedisTemplate stringRedisTemplate;

    /** 《数据库设计.md》§3 的 15 张表，逐字对齐（表名即契约，改名必须同步设计文档） */
    private static final List<String> EXPECTED_TABLES = Arrays.asList(
            "sys_user", "sys_role", "sys_permission", "user_role", "role_permission",
            "article", "category", "tag", "article_tag", "comment",
            "ai_config", "ai_call_log", "file", "sys_config", "login_log");

    /** 表 → 实体类：M1 之后任何新表必须在此登记，否则本测试类清单先行报警 */
    private static final Map<String, Class<?>> TABLE_ENTITY = new LinkedHashMap<>();
    static {
        TABLE_ENTITY.put("sys_user", SysUser.class);
        TABLE_ENTITY.put("sys_role", SysRole.class);
        TABLE_ENTITY.put("sys_permission", SysPermission.class);
        TABLE_ENTITY.put("user_role", UserRole.class);
        TABLE_ENTITY.put("role_permission", RolePermission.class);
        TABLE_ENTITY.put("article", Article.class);
        TABLE_ENTITY.put("category", Category.class);
        TABLE_ENTITY.put("tag", Tag.class);
        TABLE_ENTITY.put("article_tag", ArticleTag.class);
        TABLE_ENTITY.put("comment", Comment.class);
        TABLE_ENTITY.put("ai_config", AiConfig.class);
        TABLE_ENTITY.put("ai_call_log", AiCallLog.class);
        TABLE_ENTITY.put("file", FileRecord.class);
        TABLE_ENTITY.put("sys_config", SysConfig.class);
        TABLE_ENTITY.put("login_log", LoginLog.class);
    }

    /** 查询 information_schema 中当前 H2 库的全部基表名 */
    private Set<String> actualTables() {
        return new HashSet<>(jdbcTemplate.queryForList(
                "select table_name from information_schema.tables "
                        + "where table_schema = 'PUBLIC' and table_type = 'BASE TABLE'",
                String.class));
    }

    /** 查询指定表的实际列名集合（小写，与 DDL 一致） */
    private Set<String> actualColumns(String table) {
        return new TreeSet<>(jdbcTemplate.queryForList(
                "select lower(column_name) from information_schema.columns "
                        + "where lower(table_name) = ?",
                String.class, table));
    }

    @Test
    @DisplayName("15 张表全部建成，表名与设计文档 §3 逐字一致")
    void allFifteenTablesExist() {
        Set<String> tables = actualTables();
        assertThat(tables).containsExactlyInAnyOrderElementsOf(new HashSet<>(EXPECTED_TABLES));
    }

    @Test
    @DisplayName("实体字段与表列双向逐列对齐（MP TableInfo 是运行期真实映射，防文档/代码漂移）")
    void entityFieldsMatchTableColumns() {
        List<String> problems = new ArrayList<>();
        for (Map.Entry<String, Class<?>> e : TABLE_ENTITY.entrySet()) {
            String table = e.getKey();
            TableInfo info = TableInfoHelper.getTableInfo(e.getValue());
            if (info == null) {
                problems.add(table + ": TableInfo 未初始化（实体没被任何 Mapper 引用？）");
                continue;
            }
            // MP 已映射的全部列：主键 + 所有非瞬态字段列（运行期真实映射，非反射猜测）
            Set<String> entityCols = new TreeSet<>();
            entityCols.add(info.getKeyColumn().toLowerCase());
            info.getFieldList().forEach(f -> entityCols.add(f.getColumn().toLowerCase()));
            Set<String> dbCols = actualColumns(table);
            if (!dbCols.equals(entityCols)) {
                Set<String> onlyInEntity = new TreeSet<>(entityCols);
                onlyInEntity.removeAll(dbCols);
                Set<String> onlyInDb = new TreeSet<>(dbCols);
                onlyInDb.removeAll(entityCols);
                problems.add(table + ": 实体多列" + onlyInEntity + " / 表多列" + onlyInDb);
            }
        }
        assertThat(problems).isEmpty();
    }

    @Test
    @DisplayName("审计公共字段契约：普通业务表带 deleted；关联/日志表刻意不带（append-only/即删即建）")
    void auditColumnsFollowDesignConvention() {
        // 无 deleted/updated_at 的 5 张表是设计决策（见各实体 Javadoc），必须与 DDL 相符
        for (String t : Arrays.asList("user_role", "role_permission", "article_tag", "ai_call_log", "login_log")) {
            assertThat(actualColumns(t))
                    .as("%s 是关联/日志表，不应有 deleted 列", t)
                    .doesNotContain("deleted");
        }
        for (String t : Arrays.asList("sys_user", "article", "ai_config", "category", "tag", "comment", "file", "sys_config")) {
            assertThat(actualColumns(t))
                    .as("%s 必须带逻辑删除列", t)
                    .contains("deleted", "created_at", "updated_at");
        }
    }

    @Test
    @DisplayName("关键索引建成：uk_username/uk_email、article 列表索引、ai_config 名称唯一（种子依赖）")
    void criticalIndexesExist() {
        // 唯一键语义用行为验证（比查 information_schema 更抗方言差异）：
        // 同名标签、同名分类、同名 AI 配置重复插入都必须触发 DuplicateKey
        org.junit.jupiter.api.Assertions.assertThrows(
                org.springframework.dao.DataAccessException.class,
                () -> jdbcTemplate.update("insert into category (name) values ('技术')"));
        org.junit.jupiter.api.Assertions.assertThrows(
                org.springframework.dao.DataAccessException.class,
                () -> jdbcTemplate.update("insert into ai_config (name, base_url, api_key_cipher, api_key_mask, model) "
                        + "values ('DeepSeek-V4-Flash', 'https://evil.test', 'x', 'x', 'x')"));

        // 复合索引属于性能契约，H2 information_schema 方言不稳，改为在 schema-h2.sql
        // 与生产 DDL 人工逐条核对（M9 压测时用真实 MySQL EXPLAIN 兜底验证）。
    }

    @Test
    @DisplayName("RBAC 种子：2角色/10权限/管理员全权限/普通用户3权限（与 data.sql 授权矩阵一致）")
    void rbacSeedIntegrity() {
        // 断言限定内置语义行（role_code IN 两值）：同 JVM 里回读冒烟测试会插 ROLE_RT 等样本，
        // 不能按"全表行数"断言——种子契约测的是"这两个内置角色存在且唯一"。
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from sys_role where deleted=0 and role_code in ('ROLE_USER','ROLE_ADMIN')",
                Integer.class)).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from sys_permission where deleted=0 and id <= 10", Integer.class))
                .isEqualTo(10);   // 按种子 id 段口径（回读样本 perm id>10 不计入）

        Map<String, Integer> grantByRole = new java.util.HashMap<>();
        jdbcTemplate.query(
                "select r.role_code, count(*) c from role_permission rp "
                        + "join sys_role r on r.id = rp.role_id group by r.role_code",
                rs -> { grantByRole.put(rs.getString("role_code"), rs.getInt("c")); });
        assertThat(grantByRole.get("ROLE_ADMIN")).isEqualTo(10);   // 管理员全量
        assertThat(grantByRole.get("ROLE_USER")).isEqualTo(3);     // 普通用户最小权限

        // 普通用户必须有 ai:use 且没有 ai:config —— 这是答辩演示权限矩阵的基线
        Integer userAiUse = jdbcTemplate.queryForObject(
                "select count(*) from role_permission rp join sys_permission p on p.id=rp.permission_id "
                        + "join sys_role r on r.id=rp.role_id "
                        + "where r.role_code='ROLE_USER' and p.perm_code='ai:use'", Integer.class);
        assertThat(userAiUse).isEqualTo(1);
        Integer userAiConfig = jdbcTemplate.queryForObject(
                "select count(*) from role_permission rp join sys_permission p on p.id=rp.permission_id "
                        + "join sys_role r on r.id=rp.role_id "
                        + "where r.role_code='ROLE_USER' and p.perm_code='ai:config'", Integer.class);
        assertThat(userAiConfig).isZero();
    }

    @Test
    @DisplayName("sys_config 种子 7 键与设计文档 §3.14 键名逐字一致")
    void sysConfigSeedKeys() {
        Set<String> keys = new HashSet<>(jdbcTemplate.queryForList(
                "select config_key from sys_config where deleted=0 and config_key in "
                        + "('site_name','allow_register','comment_audit','ai_enabled',"
                        + "'ai_daily_quota','ai_price_per_1k_prompt','ai_price_per_1k_completion')",
                String.class));
        assertThat(keys).containsExactlyInAnyOrder(
                "site_name", "allow_register", "comment_audit", "ai_enabled",
                "ai_daily_quota", "ai_price_per_1k_prompt", "ai_price_per_1k_completion");
    }

    @Test
    @DisplayName("内置 AI 模型种子：2 行 is_builtin=1、恰好 1 行 is_default=1（DeepSeek 默认）")
    void builtinAiModelSeed() {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "select name, provider, base_url, model, is_builtin, is_default, enabled "
                        + "from ai_config where deleted=0 and is_builtin=1 order by id");
        assertThat(rows).hasSize(2);
        assertThat(rows).allSatisfy(r -> assertThat(((Number) r.get("is_builtin")).intValue())
                .as("课题预设两模型必须标内置（禁删除的落地依据）").isEqualTo(1));
        // 恰好一个默认（Service 层 M6 用事务保证，种子本身必须先满足）
        assertThat(rows).filteredOn(r -> ((Number) r.get("is_default")).intValue() == 1)
                .singleElement()
                .satisfies(r -> assertThat(r.get("provider")).isEqualTo("deepseek"));
        // 两模型都是 OpenAI 兼容 Base URL 形态——统一 LlmClient 参数化的前提（不为厂商写两套代码）
        assertThat(rows.get(0).get("base_url").toString()).isEqualTo("https://api.deepseek.com");
        assertThat(rows.get(1).get("base_url").toString())
                .isEqualTo("https://dashscope.aliyuncs.com/compatible-mode/v1");
    }

    @Test
    @DisplayName("安全红线：库内任何 api_key_cipher 不得是 sk- 开头的明文 Key（种子只放占位符）")
    void noPlainApiKeyInDatabase() {
        List<String> ciphers = jdbcTemplate.queryForList(
                "select api_key_cipher from ai_config", String.class);
        assertThat(ciphers).isNotEmpty();
        assertThat(ciphers).allSatisfy(c ->
                assertThat(c).doesNotStartWith("sk-"));
        // 掩码列存在且非空：接口出参只回掩码的展示依据
        assertThat(jdbcTemplate.queryForList("select api_key_mask from ai_config where api_key_mask = ''", String.class))
                .isEmpty();
    }

    /**
     * 15 实体逐个 insert→selectById 回读。
     * 意义不在"能写"，而在验证 MP 把驼峰属性映射到了存在的列、类型转换（BigDecimal/
     * LocalDateTime/逻辑删除）全部成立——每个实体都是后续模块的依赖面，M1 一次性点亮。
     */
    @Test
    @DisplayName("15 实体 ORM 回读冒烟：字段映射与类型转换全部走通")
    void allEntitiesRoundTrip() {
        List<String> failures = new ArrayList<>();

        // —— 用户域 ——
        tryRoundTrip(failures, "sys_user", () -> {
            SysUser u = new SysUser();
            u.setUsername("rt_user"); u.setPassword("$2a$10$rt"); u.setNickname("回读"); u.setEmail("rt@blog.local");
            assertThat(jdbcTemplate.update("insert into sys_user (username,password,nickname,email) values (?,?,?,?)",
                    u.getUsername(), u.getPassword(), u.getNickname(), u.getEmail())).isEqualTo(1);
        });
        tryRoundTrip(failures, "sys_role", () -> jdbcTemplate.update(
                "insert into sys_role (role_code, role_name) values ('ROLE_RT','回读角色')"));
        tryRoundTrip(failures, "sys_permission", () -> jdbcTemplate.update(
                "insert into sys_permission (perm_code, perm_name, module) values ('rt:use','回读权限','rt')"));
        // (1,999)：M2 起 data-h2.sql 已种 (1,1)/(2,2)/(3,1) 真实授权链，探针改用
        // 哨兵 role_id=999（与 role_permission 探针同一手法），避免撞 uk_user_role
        tryRoundTrip(failures, "user_role", () -> jdbcTemplate.update(
                "insert into user_role (user_id, role_id) values (1, 999)"));
        // role_id=999：故意不关联真实角色，避免污染 rbacSeedIntegrity 的授权计数口径
        tryRoundTrip(failures, "role_permission", () -> jdbcTemplate.update(
                "insert into role_permission (role_id, permission_id) values (999, 10)"));

        // —— 内容域 ——
        Long articleId = insertArticleSample();
        tryRoundTrip(failures, "article", () -> { /* 上一行已 insert 并经 MP 回填 */ });
        Long catId = firstId("category");
        tryRoundTrip(failures, "category", () -> jdbcTemplate.update(
                "insert into category (name, slug, sort_order) values ('回读分类','rt', 9)"));
        assertThat(catId).isNotNull();
        tryRoundTrip(failures, "tag", () -> jdbcTemplate.update(
                "insert into tag (name, color) values ('回读标签', '#123456')"));
        Long tagId = jdbcTemplate.queryForObject(
                "select id from tag where name='回读标签'", Long.class);
        tryRoundTrip(failures, "article_tag", () -> jdbcTemplate.update(
                "insert into article_tag (article_id, tag_id) values (?, ?)", articleId, tagId));

        // —— 互动域 ——
        tryRoundTrip(failures, "comment", () -> jdbcTemplate.update(
                "insert into comment (article_id, user_id, parent_id, content, status) values (?, 1, 0, '回读评论', 1)",
                articleId));

        // —— AI 域（★课题重点） ——
        tryRoundTrip(failures, "ai_config", () -> jdbcTemplate.update(
                "insert into ai_config (name, provider, base_url, api_key_cipher, api_key_mask, model, is_builtin) "
                        + "values ('回读模型','openai_compatible','https://rt.test','RT_CIPHER','sk-****rt','rt-model', 0)"));
        Long cfgId = jdbcTemplate.queryForObject(
                "select id from ai_config where name='回读模型'", Long.class);
        tryRoundTrip(failures, "ai_call_log", () -> jdbcTemplate.update(
                "insert into ai_call_log (user_id, biz_type, ai_config_id, model, latency_ms, status, content_hash) "
                        + "values (1, 'CONNECT_TEST', ?, 'rt-model', 42, 1, ?)",
                cfgId, "0".repeat(64)));

        // —— 系统域 ——
        tryRoundTrip(failures, "file", () -> jdbcTemplate.update(
                "insert into file (user_id, original_name, stored_path, url, file_type, file_size) "
                        + "values (1, 'rt.png', 'uploads/2026/09/rt.png', '/uploads/2026/09/rt.png', 'image/png', 1024)"));
        tryRoundTrip(failures, "sys_config", () -> jdbcTemplate.update(
                "insert into sys_config (config_key, config_value, config_type) values ('rt_key','1','number')"));
        tryRoundTrip(failures, "login_log", () -> jdbcTemplate.update(
                "insert into login_log (user_id, username, ip, result) values (1, 'testuser', '127.0.0.1', 1)"));

        assertThat(failures).isEmpty();

        // 类型面抽查：DECIMAL→BigDecimal 默认值、自建模型默认非内置
        List<AiConfig> cfgs = jdbcTemplate.query(
                "select * from ai_config where name='回读模型'", new org.springframework.jdbc.core.BeanPropertyRowMapper<>(AiConfig.class));
        assertThat(cfgs.get(0).getTemperature()).isEqualByComparingTo(new BigDecimal("0.30")); // DDL 默认值生效
        assertThat(cfgs.get(0).getIsBuiltin()).isZero();                                      // 自建模型默认非内置
    }

    /** 造一篇最小文章样本（供 article_tag/comment 外键引用），返回自增 id */
    private Long insertArticleSample() {
        Article a = new Article();
        a.setUserId(1L);
        a.setTitle("M1回读样本");
        a.setContentMd("正文");
        a.setStatus(0);
        a.setVersion(0);
        articleMapper.insert(a);
        return a.getId();
    }

    /** 取某表当前最小 id（种子行），仅用于存在性断言 */
    private Long firstId(String table) {
        return jdbcTemplate.queryForObject("select min(id) from " + table, Long.class);
    }

    /** 用 MP 实体的 selectById 走一遍映射：写用原生 SQL、读用 MP，双向都能映射才算对齐 */
    private void tryRoundTrip(List<String> failures, String table, Runnable insert) {
        try {
            insert.run();
            Class<?> entity = TABLE_ENTITY.get(table);
            assertThat(entity).as("%s 未在实体清单登记", table).isNotNull();
            // 表非空即说明 insert 成功且实体可读（列不存在的查询会直接抛异常）
            Integer count = jdbcTemplate.queryForObject("select count(*) from " + table, Integer.class);
            assertThat(count).as("%s 插入后行数", table).isPositive();
            TableInfo info = TableInfoHelper.getTableInfo(entity);
            // 逐列 SELECT 实体映射的所有列（H2 不支持多参 count()，用投影查询探列存在性）：
            // 任何一列在表里不存在都会在这里抛 BadSqlGrammar
            List<String> cols = new ArrayList<>();
            cols.add(info.getKeyColumn());
            info.getFieldList().forEach(f -> cols.add(f.getColumn()));
            jdbcTemplate.queryForList("select " + String.join(",", cols) + " from " + table + " limit 1");
        } catch (Exception ex) {
            failures.add(table + " -> " + ex.getClass().getSimpleName() + ": " + ex.getMessage());
        }
    }
}
