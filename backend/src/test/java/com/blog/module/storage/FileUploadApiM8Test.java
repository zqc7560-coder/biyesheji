package com.blog.module.storage;

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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * M8 文件上传接口级测试（POST /api/files/upload，API 文档 §8）。
 *
 * <p>覆盖四条契约 + 一条安全红线：
 * ① 登录闸门（匿名 401/1001）；② 三卡校验的接口面（扩展名/魔数/同族各自 3001、
 * 超限 3002）；③ 响应结构 {url, fileId, size} 与 file 表落库形态；
 * ④ type 参数白名单；⑤ 落盘文件真实存在于 blog.upload.dir（test profile 指到
 * target/test-uploads，不污染工作区）。
 *
 * <p>基建沿用 M4/M5：内存 Redis @Primary、类级 @Transactional 回滚、readEnvelope
 * 显式 UTF-8（MockMvc ISO-8859-1 坑）。身份物料：testuser(id=1, ROLE_USER) /
 * other(id=2, ROLE_ADMIN)，种子口令 password123。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@DisplayName("M8 文件上传: 登录闸/三卡校验/响应契约/file表落库")
class FileUploadApiM8Test {

    private static final String SEED_PWD = "password123";

    /** 合法 PNG 样本：完整 8 字节签名 + 伪造尾部（校验器只嗅头 12 字节） */
    private static final byte[] PNG_BYTES = new byte[]{
            (byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3, 4};

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @org.springframework.beans.factory.annotation.Value("${blog.upload.dir}")
    private String uploadDir;

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

    private MockMultipartFile png(String name, String contentType) {
        return new MockMultipartFile("file", name, contentType, PNG_BYTES);
    }

    // ==================== 用例 ====================

    @Test
    @DisplayName("匿名上传 → 401/1001（登录闸门）")
    void anonymousRejected() throws Exception {
        mockMvc.perform(multipart("/api/files/upload").file(png("a.png", "image/png")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(1001));
    }

    @Test
    @DisplayName("合法 PNG 上传：响应 {url,fileId,size} 契约 + file 表留痕 + 磁盘真实落文件")
    void happyPath() throws Exception {
        String token = loginForToken("testuser");
        MvcResult res = mockMvc.perform(multipart("/api/files/upload")
                        .file(png("我的头像.png", "image/png"))
                        .param("type", "cover")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn();

        Map<String, Object> data = castMap(readEnvelope(res).get("data"));
        String url = (String) data.get("url");
        assertThat(url).matches("/uploads/\\d{4}/\\d{2}/[0-9a-f]{32}\\.png");
        assertThat(((Number) data.get("fileId")).longValue()).isPositive();
        // JSON 数字经 Map 反序列化按量级选 Integer/Long——数值断言先过 Number 归一（别写死 long 字面量等号）
        assertThat(((Number) data.get("size")).longValue()).isEqualTo((long) PNG_BYTES.length);

        // file 表列形态（§3.13）：stored_path 带 uploads/ 前缀、file_type 归一 MIME、归属登录者
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "select user_id, original_name, stored_path, file_type, storage_type from file where id = ?",
                ((Number) data.get("fileId")).longValue());
        assertThat(row.get("user_id")).isEqualTo(1L);              // testuser 种子 id=1
        assertThat(row.get("original_name")).isEqualTo("我的头像.png"); // 原始名仅展示，乱码=UTF-8 链路断
        assertThat(row.get("stored_path")).isEqualTo("uploads" + url.substring("/uploads".length()));
        assertThat(row.get("file_type")).isEqualTo("image/png");
        assertThat(row.get("storage_type")).isEqualTo(0);

        // 落盘事实：test profile 把 blog.upload.dir 指到 target/test-uploads
        Path disk = Paths.get(uploadDir).toAbsolutePath().normalize()
                .resolve(url.substring("/uploads/".length()));
        assertThat(Files.exists(disk)).as("上传文件应真实存在于 %s", disk).isTrue();
    }

    @Test
    @DisplayName("三卡拒绝面：txt 扩展名 3001；HTML 换皮 .png 3001（魔数卡）；jpg 壳装 PNG 数据 3001（同族卡）")
    void validationMatrix() throws Exception {
        String token = loginForToken("testuser");

        // 第一卡：扩展名白名单
        mockMvc.perform(multipart("/api/files/upload")
                        .file(new MockMultipartFile("file", "note.txt", "text/plain",
                                "hello".getBytes()))
                        .header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.code").value(3001));

        // 第三卡：扩展名过、但文件头是 HTML——M2 版本会放行（Content-Type 客户端自报），
        // M8 魔数卡必须拦（/uploads 静态直出的存储型 XSS 跳板）
        mockMvc.perform(multipart("/api/files/upload")
                        .file(new MockMultipartFile("file", "evil.png", "image/png",
                                "<script>alert(1)</script>".getBytes()))
                        .header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.code").value(3001));

        // 同族卡：.jpg 扩展名 + 真 PNG 数据（两卡都"合法"但互相矛盾）
        mockMvc.perform(multipart("/api/files/upload")
                        .file(png("fake.jpg", "image/jpeg"))
                        .header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.code").value(3001));
    }

    @Test
    @DisplayName("超 5MB → 3002（大小闸在三卡之前，先看体积少读入内存）")
    void tooLarge() throws Exception {
        String token = loginForToken("testuser");
        byte[] big = new byte[5 * 1024 * 1024 + 1];
        System.arraycopy(PNG_BYTES, 0, big, 0, PNG_BYTES.length); // 头部仍合法，只超体积
        mockMvc.perform(multipart("/api/files/upload")
                        .file(new MockMultipartFile("file", "big.png", "image/png", big))
                        .header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.code").value(3002));
    }

    @Test
    @DisplayName("type 白名单：脏 type → 400；缺省 type=article 可用")
    void bizTypeGate() throws Exception {
        String token = loginForToken("testuser");
        mockMvc.perform(multipart("/api/files/upload")
                        .file(png("a.png", "image/png"))
                        .param("type", "shell")
                        .header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.code").value(400));
        mockMvc.perform(multipart("/api/files/upload")
                        .file(png("a.png", "image/png"))
                        .header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.code").value(0));
    }

    @Test
    @DisplayName("普通用户即可上传（UC-U-04 写作含插图，无权限点要求）")
    void anyLoggedInUserCanUpload() throws Exception {
        String token = loginForToken("testuser");   // ROLE_USER，无任何 manage 权限
        mockMvc.perform(multipart("/api/files/upload")
                        .file(png("a.png", "image/png"))
                        .param("type", "article")
                        .header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.code").value(0));
    }

    @Test
    @DisplayName("同名文件重复上传互不覆盖（uuid 重命名：两条 file 行两个 url）")
    void sameNameNoOverwrite() throws Exception {
        String token = loginForToken("testuser");
        java.util.List<Object> urls = new java.util.ArrayList<>();
        for (int i = 0; i < 2; i++) {
            Map<String, Object> data = castMap(readEnvelope(mockMvc.perform(
                    multipart("/api/files/upload")
                            .file(png("dup.png", "image/png"))
                            .header("Authorization", "Bearer " + token))
                    .andReturn()).get("data"));
            urls.add(data.get("url"));
        }
        assertThat((List<?>) urls).doesNotHaveDuplicates();
    }
}
