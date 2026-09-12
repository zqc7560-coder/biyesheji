package com.blog.module.auth;

import com.blog.common.BizCode;
import com.blog.config.AdminBootstrapRunner;
import com.blog.module.auth.dto.RegisterDTO;
import com.blog.module.auth.entity.LoginLog;
import com.blog.module.auth.mapper.LoginLogMapper;
import com.blog.support.InMemoryRedis;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
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
import org.springframework.mock.web.MockMultipartFile;
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
 * 认证模块接口级集成测试（M2）—— 目标：《项目计划.md》阶段三任务 7"接口用例 15+
 * （含越权矩阵）"与 M2 里程碑验收："注册→登录→带 JWT 访问受保护接口→登出后旧 Token
 * 返回 401；ADMIN 接口用 USER Token 访问返回 1005"。
 *
 * <p>基建决策：
 * <ol>
 *   <li>StringRedisTemplate 用 @Primary 内存版（InMemoryRedis）覆盖自动配置——
 *       登出/改密/禁号的"令牌作废"流转必须有真实读写语义才测得出东西（类注释详述）；</li>
 *   <li>类级 @Transactional：测试方法回滚保证用例间数据零污染。注意两个例外——
 *       a) writeLoginLog 是 REQUIRES_NEW，日志提交独立于外层回滚，正好用来断言
 *       "失败登录必须留痕"的设计意图；b) MockMvc 请求与测试同线程，共享该事务。</li>
 * </ol>
 *
 * <p>种子前提（data-h2.sql）：testuser/other（password123 散列，分别挂 USER/ADMIN 角色）、
 * disabled（禁用态样本）。admin 不在种子里——由本测试显式驱动 AdminBootstrapRunner
 * 创建（生产同款引导路径）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@DisplayName("M2 认证接口: 注册/登录/RBAC/登出/改密/续期 全链路")
class AuthApiM2Test {

    /** 测试口令常量：与 data-h2.sql 预计算 BCrypt 散列配对（种子用户明文） */
    private static final String SEED_PWD = "password123";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private LoginLogMapper loginLogMapper;

    /** admin 引导器：显式驱动（@SpringBootTest 不走 SpringApplication.run，Runner 不会自动执行） */
    @Autowired
    private AdminBootstrapRunner adminBootstrap;

    /**
     * 内存 Redis 覆盖真实自动配置：@Primary 保证 TokenBlacklistService 注入的是它。
     * StringRedisTemplate 的自动配置是 @ConditionalOnMissingBean，自定义 Bean 直接顶掉。
     */
    @TestConfiguration
    static class FakeRedisConfig {
        @Bean
        @Primary
        StringRedisTemplate inMemoryRedisTemplate() {
            return InMemoryRedis.template();
        }
    }

    // ==================== 小工具 ====================

    /** 发起 JSON POST 并解析统一信封（返回 code + data 节点，断言围绕它们做） */
    private Map<String, Object> postJson(String uri, Object body) throws Exception {
        MvcResult result = mockMvc.perform(post(uri)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
        return readEnvelope(result);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readEnvelope(MvcResult result) throws Exception {
        // HTTP 4xx 也携带统一信封（这正是契约的一部分，状态码断言放在具体用例）。
        // 必须显式 UTF-8 解码字节：writeError/ResponseEntity 输出的 Content-Type 是
        // application/json 不带 charset 参数，MockHttpServletResponse#getContentAsString()
        // 按规范回退 ISO-8859-1 —— 中文 message（如"改名了"）会读成乱码。
        // 真实容器（Tomcat/Nginx）对 JSON 直出 UTF-8 字节，无此问题，属测试侧解码口径修正。
        return objectMapper.readValue(
                new String(result.getResponse().getContentAsByteArray(),
                        java.nio.charset.StandardCharsets.UTF_8),
                Map.class);
    }

    /** 登录拿令牌（失败直接抛 AssertionError——令牌是后续用例的前置物料，不该静默 null） */
    private String loginForToken(String username, String password) throws Exception {
        Map<String, Object> body = postJson("/api/auth/login",
                Map.of("username", username, "password", password));
        assertThat((Integer) body.get("code")).as("登录应成功: %s", body.get("message")).isZero();
        Map<String, Object> data = (Map<String, Object>) body.get("data");
        return (String) data.get("token");
    }

    // ==================== 注册 ====================

    @Test
    @DisplayName("注册成功：新账号立刻可登录，默认挂 ROLE_USER + 3 权限点")
    void registerThenLogin() throws Exception {
        Map<String, Object> reg = postJson("/api/auth/register", Map.of(
                "username", "m2user01", "password", "Abcd@1234",
                "nickname", "M2用户", "email", "m2user01@blog.local"));
        assertThat((Integer) reg.get("code")).isZero();
        Map<String, Object> data = castMap(reg.get("data"));
        assertThat(((Number) data.get("userId")).longValue()).isPositive();

        // 注册→登录闭环：证明散列与角色挂载正确（登录响应里 roles/perms 是聚合链路的输出）
        Map<String, Object> login = postJson("/api/auth/login",
                Map.of("username", "m2user01", "password", "Abcd@1234"));
        assertThat((Integer) login.get("code")).isZero();
        Map<String, Object> user = castMap(castMap(login.get("data")).get("user"));
        assertThat(castList(user.get("roles"))).containsExactly("ROLE_USER");
        assertThat(castList(user.get("perms")))
                .contains("article:save", "article:delete", "ai:use")
                .doesNotContain("ai:config", "user:manage");   // 最小权限：管理员口不能漏
    }

    @Test
    @DisplayName("注册查重：重复用户名 1101 / 重复邮箱 1102")
    void registerDuplicateChecks() throws Exception {
        Map<String, Object> dupName = postJson("/api/auth/register", Map.of(
                "username", "testuser", "password", "Abcd@1234",
                "nickname", "撞名", "email", "fresh@blog.local"));
        assertThat((Integer) dupName.get("code")).isEqualTo(1101);

        Map<String, Object> dupEmail = postJson("/api/auth/register", Map.of(
                "username", "freshname", "password", "Abcd@1234",
                "nickname", "撞邮箱", "email", "test@blog.local"));
        assertThat((Integer) dupEmail.get("code")).isEqualTo(1102);
    }

    @Test
    @DisplayName("注册参数校验：弱密码/非法用户名/非法邮箱 → 400")
    void registerValidationRules() throws Exception {
        MvcResult weak = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", "ok_user_1", "password", "12345678",   // 纯数字：缺字母
                                "nickname", "弱密码", "email", "weak@blog.local"))))
                .andExpect(status().isBadRequest())
                .andReturn();
        assertThat((Integer) readEnvelope(weak).get("code")).isEqualTo(400);

        MvcResult badName = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", "ab",   // 少于 4 位
                                "password", "Abcd@1234", "nickname", "短名", "email", "x@blog.local"))))
                .andReturn();
        assertThat((Integer) readEnvelope(badName).get("code")).isEqualTo(400);
    }

    @Test
    @DisplayName("注册开关：allow_register=false 时拒绝注册（sys_config 热生效）")
    void registerSwitchOff() throws Exception {
        jdbcTemplate.update("update sys_config set config_value='false' where config_key='allow_register'");
        try {
            Map<String, Object> body = postJson("/api/auth/register", Map.of(
                    "username", "m2user02", "password", "Abcd@1234",
                    "nickname", "关注册", "email", "m2user02@blog.local"));
            assertThat((Integer) body.get("code")).isEqualTo(BizCode.FORBIDDEN.getCode());
        } finally {
            // 恢复开关：本类 @Transactional 也会回滚这行 update，finally 是双保险
            // （防止未来有人给该用例摘掉 @Transactional 后污染同库其它上下文）
            jdbcTemplate.update("update sys_config set config_value='true' where config_key='allow_register'");
        }
    }

    // ==================== 登录 ====================

    @Test
    @DisplayName("登录成功：token/expiresIn/user 齐备 + last_login_at 回写 + 成功日志")
    void loginHappyPath() throws Exception {
        Map<String, Object> body = postJson("/api/auth/login",
                Map.of("username", "testuser", "password", SEED_PWD));
        assertThat((Integer) body.get("code")).isZero();
        Map<String, Object> data = castMap(body.get("data"));
        assertThat((String) data.get("token")).isNotBlank();
        assertThat(((Number) data.get("expiresIn")).longValue()).isEqualTo(7200);

        Integer touched = jdbcTemplate.queryForObject(
                "select count(*) from sys_user where username='testuser' and last_login_at is not null",
                Integer.class);
        assertThat(touched).isEqualTo(1);

        // 成功日志 result=1（REQUIRES_NEW 独立事务：本用例事务回滚也不会吞掉这行——
        // 反过来断言查得到，即"审计留痕不参与业务事务成败"的实证）
        List<LoginLog> logs = loginLogMapper.selectList(new LambdaQueryWrapper<LoginLog>()
                .eq(LoginLog::getUsername, "testuser").eq(LoginLog::getResult, 1));
        assertThat(logs).isNotEmpty();
    }

    @Test
    @DisplayName("防枚举：不存在账号与密码错误统一 1002，login_log 内部分 4/2 留痕")
    void loginFailuresDontLeakAccountExistence() throws Exception {
        Map<String, Object> noSuchUser = postJson("/api/auth/login",
                Map.of("username", "ghost_user", "password", "Whatever@123"));
        assertThat((Integer) noSuchUser.get("code")).isEqualTo(1002);

        Map<String, Object> badPwd = postJson("/api/auth/login",
                Map.of("username", "testuser", "password", "WrongPwd@999"));
        assertThat((Integer) badPwd.get("code")).isEqualTo(1002);
        // 两次响应的 code+message 完全一致：攻击者拿不到任何区分信号
        assertThat(noSuchUser.get("message")).isEqualTo(badPwd.get("message"));

        // 审计侧必须能区分（爆破排查要看的就是"不存在"与"密码错"的配比）
        assertThat(loginLogMapper.selectList(new LambdaQueryWrapper<LoginLog>()
                .eq(LoginLog::getUsername, "ghost_user").eq(LoginLog::getResult, 4))).hasSize(1);
        assertThat(loginLogMapper.selectList(new LambdaQueryWrapper<LoginLog>()
                .eq(LoginLog::getUsername, "testuser").eq(LoginLog::getResult, 2))).hasSize(1);
    }

    @Test
    @DisplayName("禁用账号登录 → 403 + 1003 + result=3 留痕")
    void disabledAccountLogin() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("username", "disabled", "password", SEED_PWD))))
                .andExpect(status().isForbidden())
                .andReturn();
        Map<String, Object> body = readEnvelope(result);
        assertThat((Integer) body.get("code")).isEqualTo(1003);
        assertThat(loginLogMapper.selectList(new LambdaQueryWrapper<LoginLog>()
                .eq(LoginLog::getUsername, "disabled").eq(LoginLog::getResult, 3))).hasSize(1);
    }

    // ==================== 令牌生命周期（M2 里程碑验收主链） ====================

    @Test
    @DisplayName("带 JWT 访问受保护接口成功 → 登出 → 旧 Token 立刻 401(1001)")
    void loginAccessLogoutDeadToken() throws Exception {
        String token = loginForToken("testuser", SEED_PWD);

        // 受保护接口 + me：令牌身份完整可用
        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.username").value("testuser"))
                .andExpect(jsonPath("$.data.email").value("test@blog.local"));   // 本人可见邮箱

        mockMvc.perform(post("/api/auth/logout").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        // 登出即黑名单：旧令牌再访问必须 401/1001（不是 403——"你是谁"已经不成立）
        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(1001));
    }

    @Test
    @DisplayName("无令牌/垃圾令牌访问受保护接口 → 401 + 统一信封(1001)")
    void anonymousAndGarbageToken() throws Exception {
        mockMvc.perform(get("/api/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(1001));

        // 垃圾令牌：过滤器验签失败直接写信封（决策 2 路径），同样 1001
        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer not.a.jwt"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(1001));
    }

    @Test
    @DisplayName("滑动续期：剩余≥30min 不换发；<30min 换发且旧令牌作废")
    void slidingRefresh() throws Exception {
        // 正常 TTL 令牌：剩余 2h ≥30min → renewed=false，不换发
        String token = loginForToken("testuser", SEED_PWD);
        Map<String, Object> noRenew = castMap(readEnvelope(mockMvc.perform(
                post("/api/auth/refresh").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andReturn()).get("data"));
        assertThat((Boolean) noRenew.get("renewed")).isFalse();

        // 手工签一个 5 分钟 TTL 的同密钥令牌：触发换发路径
        com.blog.security.JwtProperties props = new com.blog.security.JwtProperties();
        props.setSecret("dev-only-secret-change-me-0123456789abcdef");   // 与 application.yml dev 默认同值
        props.setExpireSeconds(300);
        com.blog.security.JwtService shortLived = new com.blog.security.JwtService(props);
        String nearExpiry = shortLived.issue(1L, "testuser", "测试用户", null,
                List.of("ROLE_USER"), List.of("article:save"));

        Map<String, Object> renewed = castMap(readEnvelope(mockMvc.perform(
                post("/api/auth/refresh").header("Authorization", "Bearer " + nearExpiry))
                .andExpect(status().isOk()).andReturn()).get("data"));
        assertThat((Boolean) renewed.get("renewed")).isTrue();
        assertThat((String) renewed.get("token")).isNotBlank();

        // 旋转语义：旧令牌拉黑，再用即 401（防"新旧并行可用"）
        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + nearExpiry))
                .andExpect(status().isUnauthorized());
    }

    // ==================== RBAC 越权矩阵 ====================

    @Test
    @DisplayName("M2 验收核心：USER 令牌访问 ADMIN 接口 → 403 + 1005")
    void userTokenBlockedFromAdminApi() throws Exception {
        String userToken = loginForToken("testuser", SEED_PWD);   // ROLE_USER，无 user:manage

        mockMvc.perform(get("/api/admin/users").header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(1005));
    }

    @Test
    @DisplayName("ADMIN 令牌可访问用户治理接口（分页载荷结构正确）")
    void adminTokenCanPageUsers() throws Exception {
        // 用种子 ADMIN 样本 other（角色链 data-h2.sql 已挂载）
        String adminToken = loginForToken("other", SEED_PWD);

        mockMvc.perform(get("/api/admin/users").header("Authorization", "Bearer " + adminToken)
                        .param("keyword", "testuser"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.list[0].username").value("testuser"))
                .andExpect(jsonPath("$.data.pageNum").value(1));
    }

    @Test
    @DisplayName("禁用即踢线：管理员禁 user → 该用户全部旧令牌 401")
    void disableAccountKillsLiveTokens() throws Exception {
        String adminToken = loginForToken("other", SEED_PWD);
        String victimToken = loginForToken("testuser", SEED_PWD);

        Long victimId = jdbcTemplate.queryForObject(
                "select id from sys_user where username='testuser'", Long.class);

        mockMvc.perform(patch("/api/admin/users/" + victimId + "/status")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        // 水位生效：受害者的"未登出"令牌在禁用瞬间作废（2h TTL 内禁用形同虚设的老问题）
        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + victimToken))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(1001));
    }

    @Test
    @DisplayName("护栏：管理员不能禁用自己（防单人课设锁死后台）")
    void adminCannotDisableSelf() throws Exception {
        String adminToken = loginForToken("other", SEED_PWD);
        Long adminId = jdbcTemplate.queryForObject(
                "select id from sys_user where username='other'", Long.class);

        mockMvc.perform(patch("/api/admin/users/" + adminId + "/status")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":0}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(1005));
    }

    // ==================== 资料 / 改密 ====================

    @Test
    @DisplayName("查他人资料隐去邮箱；改自己资料生效")
    void profileVisibilityAndEdit() throws Exception {
        String token = loginForToken("testuser", SEED_PWD);
        Long otherId = jdbcTemplate.queryForObject(
                "select id from sys_user where username='other'", Long.class);

        Map<String, Object> body = castMap(readEnvelope(mockMvc.perform(
                get("/api/users/profile").header("Authorization", "Bearer " + token)
                        .param("userId", String.valueOf(otherId)))
                .andExpect(status().isOk()).andReturn()).get("data"));
        assertThat(body.get("email")).isNull();          // PII 分级：他人不可见
        assertThat(body.get("username")).isEqualTo("other");

        MvcResult edited = mockMvc.perform(put("/api/users/profile")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "nickname", "改名了", "email", "renamed@blog.local", "bio", "hi"))))
                .andExpect(status().isOk())
                .andReturn();
        Map<String, Object> data = castMap(readEnvelope(edited).get("data"));
        assertThat(data.get("nickname")).isEqualTo("改名了");
        assertThat(data.get("email")).isEqualTo("renamed@blog.local");  // 本人场景回显
    }

    @Test
    @DisplayName("改密：旧密码错→1002；改后全端下线 + 新密码可登录 + message 提示重登")
    void changePasswordFullFlow() throws Exception {
        // —— 先注册一个专属样本账号，改密不影响其它用例的种子口令 ——
        postJson("/api/auth/register", Map.of("username", "m2pwduser01",
                "password", "Abcd@1234", "nickname", "改密样本", "email", "pwd1@blog.local"));
        String token = loginForToken("m2pwduser01", "Abcd@1234");

        // 旧密码错误：401 + 1002（不产生任何状态变更）
        mockMvc.perform(put("/api/users/password")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("oldPassword", "WrongOld@1", "newPassword", "NewPwd@5678"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(1002));

        // 改密成功：文案按 API 文档约定 + 当前令牌即刻作废
        mockMvc.perform(put("/api/users/password")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("oldPassword", "Abcd@1234", "newPassword", "NewPwd@5678"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.message").value("密码已更新，请重新登录"));

        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());   // 全端下线

        // 新密码登录闭环（BCrypt 换发成功的最直接证据）
        String fresh = loginForToken("m2pwduser01", "NewPwd@5678");
        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + fresh))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.username").value("m2pwduser01"));
    }

    @Test
    @DisplayName("头像上传：png 落盘 file 表登记 + 资料回显新 URL；txt 拒 3001")
    void avatarUpload() throws Exception {
        String token = loginForToken("testuser", SEED_PWD);
        MockMultipartFile png = new MockMultipartFile("file", "me.png", "image/png",
                // M8 起加魔数第三卡：给足完整 8 字节 PNG 签名（0x89 P N G 0D 0A 1A 0A）+尾部数据
                new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3});

        MvcResult ok = mockMvc.perform(multipart("/api/users/avatar").file(png)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn();
        String avatar = (String) castMap(readEnvelope(ok).get("data")).get("avatar");
        assertThat(avatar).startsWith("/uploads/").contains(".png");

        // file 表留痕：uk_stored 路径 + 归属人
        Integer rows = jdbcTemplate.queryForObject(
                "select count(*) from file where url = ? and user_id = 1", Integer.class, avatar);
        assertThat(rows).isEqualTo(1);

        MockMultipartFile txt = new MockMultipartFile("file", "hack.txt", "text/plain",
                "console.log(1)".getBytes());
        mockMvc.perform(multipart("/api/users/avatar").file(txt)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(3001));
    }

    // ==================== admin 引导 ====================

    @Test
    @DisplayName("AdminBootstrapRunner：首次创建 admin+全权限，二次执行幂等不改密")
    void adminBootstrapIdempotent() throws Exception {
        adminBootstrap.run(null);       // 种子无 admin → 创建
        adminBootstrap.run(null);       // 已存在 → 跳过（幂等）

        Integer admins = jdbcTemplate.queryForObject(
                "select count(*) from sys_user where username='admin' and deleted=0", Integer.class);
        assertThat(admins).isEqualTo(1);

        // 引导出的 admin 可登录且拿到 10 个权限点（角色链装配的端到端证据）
        String adminToken = loginForToken("admin", "Admin@123");
        Map<String, Object> me = castMap(readEnvelope(mockMvc.perform(
                get("/api/auth/me").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk()).andReturn()).get("data"));
        assertThat(castList(me.get("roles"))).contains("ROLE_ADMIN");
        assertThat(castList(me.get("perms"))).hasSize(10);
    }

    // ==================== 工具 ====================

    @SuppressWarnings("unchecked")
    private Map<String, Object> castMap(Object o) {
        return (Map<String, Object>) o;
    }

    @SuppressWarnings("unchecked")
    private List<String> castList(Object o) {
        return (List<String>) o;
    }
}
