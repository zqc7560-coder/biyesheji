package com.blog;

import com.blog.module.article.entity.Article;
import com.blog.module.article.mapper.ArticleMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 工程骨架级集成测试（H2 内存库，MySQL 兼容模式）。
 *
 * <p>验证骨架本身的四件事，全部是后续 12 个模块的地基：
 * <ol>
 *   <li>Spring 容器能启动（自动配置没打架：MP + Redis + Knife4j 共存）；</li>
 *   <li>测试数据源确实是 H2 —— 满足"测试不依赖真实 MySQL 账号"的环境约束；</li>
 *   <li>MyBatis-Plus 插件链按注册生效：自增主键回填、乐观锁、逻辑删除、分页；</li>
 *   <li>统一响应体信封在真实 MVC 链路（/api/health）上结构正确。</li>
 * </ol>
 *
 * <p>Redis 用 @MockBean 顶掉：测试必须与外部中间件解耦
 * ——任何人 clone 下来没起 Redis 也能 mvn test 全绿，这是 CI 化的前提。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("骨架集成: 容器启动 + H2 数据源 + MP 插件链 + 统一响应体")
class BlogServerApplicationTests {

    @Autowired
    private ArticleMapper articleMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** 真实 MVC 链路的测试入口（不起端口，走 MockMvc） */
    @Autowired
    private MockMvc mockMvc;

    /** 顶掉真实 Redis 客户端：健康探测的 redis 分支走 mock 返回 UP */
    @MockBean
    private StringRedisTemplate stringRedisTemplate;

    /** 造一篇最小合法文章（标题/正文非空，状态草稿） */
    private Article newArticle(String title, Long authorId) {
        Article a = new Article();
        a.setUserId(authorId);
        a.setTitle(title);
        a.setContentMd("# " + title + "\n\n正文");
        a.setStatus(0);
        a.setVersion(0);
        return a;
    }

    @Test
    @DisplayName("容器启动即骨架装配成功：核心 Bean 全部可注入")
    void contextLoads() {
        assertThat(articleMapper).isNotNull();
        assertThat(mockMvc).isNotNull();
    }

    @Test
    @DisplayName("测试数据源是 H2 内存库，而非本机 MySQL")
    void dataSourceIsH2() {
        // h2version() 只在 H2 上存在：查询能执行本身就证明换库成功，
        // 满足环境约束「测试用 H2，运行时才连真实 MySQL」。
        String product = jdbcTemplate.queryForObject("select h2version()", String.class);

        assertThat(product).isNotBlank();
    }

    @Test
    @DisplayName("MP 基础链路：insert 自增主键回填、驼峰↔下划线列映射")
    void insertFillsIdAndMapsColumns() {
        Article a = newArticle("列映射冒烟", 1L);
        a.setSummarySource(1);   // 验证 @TableField("summary_source") 下划线列映射
        a.setPublishedAt(LocalDateTime.now());

        int rows = articleMapper.insert(a);

        assertThat(rows).isEqualTo(1);
        assertThat(a.getId()).isNotNull();  // IdType.AUTO 主键回填成功

        Article loaded = articleMapper.selectById(a.getId());
        assertThat(loaded.getSummarySource()).isEqualTo(1);
        assertThat(loaded.getContentMd()).contains("正文");
        assertThat(loaded.getVersion()).isEqualTo(0);
    }

    @Test
    @DisplayName("乐观锁插件：version 不匹配的 update 影响 0 行（TC-F-04 的地基）")
    void optimisticLockBlocksStaleUpdate() {
        articleMapper.insert(newArticle("乐观锁样本", 1L));
        Article inserted = lastInserted();

        // 模拟两个"编辑窗口"读到同一版本
        Article window1 = articleMapper.selectById(inserted.getId());
        Article window2 = articleMapper.selectById(inserted.getId());

        window1.setTitle("窗口1先保存");
        assertThat(articleMapper.updateById(window1)).isEqualTo(1);   // 成功且 version 0→1

        window2.setTitle("窗口2后保存");
        assertThat(articleMapper.updateById(window2)).isZero();       // WHERE version=0 已不成立

        Article after = articleMapper.selectById(inserted.getId());
        assertThat(after.getTitle()).isEqualTo("窗口1先保存");          // 库里是先保存者
        assertThat(after.getVersion()).isEqualTo(1);                  // 版本号推进了一位
    }

    @Test
    @DisplayName("逻辑删除：deleteById 转 UPDATE deleted=1，查询自动过滤已删行")
    void logicDeleteHidesRowFromQueries() {
        articleMapper.insert(newArticle("将被逻辑删除", 1L));
        Article target = lastInserted();

        assertThat(articleMapper.deleteById(target.getId())).isEqualTo(1);
        assertThat(articleMapper.selectById(target.getId())).isNull();           // MP 自动拼 deleted=0
        assertThat(articleMapper.selectCount(new LambdaQueryWrapper<Article>()
                .eq(Article::getId, target.getId()))).isZero();
        // 底层行其实还在（回收站/审计语义）：绕过 MP 直接看物理表
        Integer rawDeleted = jdbcTemplate.queryForObject(
                "select deleted from article where id = ?", Integer.class, target.getId());
        assertThat(rawDeleted).isEqualTo(1);
    }

    @Test
    @DisplayName("分页插件：selectPage 返回 records/total/pages 正确")
    void paginationPluginWorks() {
        for (int i = 0; i < 5; i++) {
            articleMapper.insert(newArticle("分页样本-" + i, 2L));
        }

        Page<Article> page = articleMapper.selectPage(new Page<>(1, 2),
                new LambdaQueryWrapper<Article>().eq(Article::getUserId, 2L)
                        .orderByAsc(Article::getId));

        assertThat(page.getRecords()).hasSize(2);          // pageSize=2 生效
        assertThat(page.getTotal()).isGreaterThanOrEqualTo(5);
        assertThat(page.getPages()).isGreaterThanOrEqualTo(3);
    }

    @Test
    @DisplayName("H2 种子用户可查：username 唯一键生效（1101 错误的底层保障）")
    void seedUsersExistAndUnique() {
        Integer count = jdbcTemplate.queryForObject(
                "select count(*) from sys_user where deleted = 0", Integer.class);
        assertThat(count).isGreaterThanOrEqualTo(2);

        // 重复用户名应触发唯一键冲突——证明 uk_username 建成了，
        // Service 层 1101「用户名已存在」正是捕获这个异常转出来的。
        org.junit.jupiter.api.Assertions.assertThrows(
                org.springframework.dao.DuplicateKeyException.class,
                () -> jdbcTemplate.update("insert into sys_user (username, password, nickname, email) "
                        + "values ('testuser', 'x', 'y', 'z@blog.local')"));
    }

    @Test
    @DisplayName("/api/health 真实链路：统一响应体 {code,message,data,timestamp} + 依赖连通态")
    void healthEndpointReturnsUnifiedEnvelope() throws Exception {
        // mock Redis ping：SDR 3.x 的 RedisConnection.ping() 返回 String "PONG"；
        // execute(callback) 被拦截后手动把回调作用到 mock 连接上，走真实判定逻辑
        RedisConnection conn = mock(RedisConnection.class);
        when(conn.ping()).thenReturn("PONG");
        when(stringRedisTemplate.execute(
                org.mockito.ArgumentMatchers.<org.springframework.data.redis.core.RedisCallback<?>>any()))
                .thenAnswer(invocation -> {
                    org.springframework.data.redis.core.RedisCallback<?> cb = invocation.getArgument(0);
                    return cb.doInRedis(conn);
                });

        mockMvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                // 信封四字段齐全——这是前端 Axios 拦截器的依赖项
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.message").value("success"))
                .andExpect(jsonPath("$.timestamp").isNumber())
                // MySQL 走真 H2 应 UP；Redis 走 mock 也 UP → overall UP
                .andExpect(jsonPath("$.data.mysql").value("UP"))
                .andExpect(jsonPath("$.data.overall").value("UP"));
    }

    /** 按 id 倒序取最近插入的一条（测试内自增序列单调，规避断言脆弱性） */
    private Article lastInserted() {
        List<Article> list = articleMapper.selectList(new LambdaQueryWrapper<Article>()
                .orderByDesc(Article::getId).last("limit 1"));
        return list.isEmpty() ? null : list.get(0);
    }
}
