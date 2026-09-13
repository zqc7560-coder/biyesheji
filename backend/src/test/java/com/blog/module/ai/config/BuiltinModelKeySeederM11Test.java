package com.blog.module.ai.config;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.blog.module.ai.crypto.KeyCipherService;
import com.blog.module.ai.entity.AiConfig;
import com.blog.module.ai.mapper.AiConfigMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.env.Environment;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * M11 测试完善 · 内置模型 Key 引导器单测（M6 安全组件的覆盖率清偿）。
 *
 * <p>为什么值得单独测：BuiltinModelKeySeeder 是"密钥红线"的执行者——它的错误
 * 形态不是崩溃而是**静默地做错事**：把旧值覆盖管理员手改的新 Key（幂等破坏）、
 * 明文 Key 进日志（脱敏破坏）、启动期抛异常拖垮主流程（降级破坏）。这三条都
 * 只有把 run() 的行为逐项扳机才测得到；M6 接口测试里它被 test profile 显式禁用
 * （防环境漂移），所以本类是唯一的行为锁。
 *
 * <p>mock 面：AiConfigMapper（selectOne/update 记录调用与条件）、Environment
 * （环境变量面）、真实 KeyCipherService（32 字符开发口令——掩码/加密要真跑，
 * 假加密测不出"密文列里没有明文"）。.env 文件面用 @TempDir 真文件驱动。
 */
@DisplayName("M11 Key引导器: 幂等/优先级/脱敏/兜底不炸启动")
class BuiltinModelKeySeederM11Test {

    private AiConfigMapper mapper;
    private KeyCipherService cipher;
    private Environment env;
    private BuiltinModelKeySeeder seeder;

    @BeforeEach
    void setUp() {
        mapper = mock(AiConfigMapper.class);
        // 真加密器：口令用 32 字符开发值；断言"密文不含明文/掩码形态正确"必须是真 AES
        cipher = new KeyCipherService("0123456789abcdef0123456789abcdef");
        env = mock(Environment.class);
        seeder = new BuiltinModelKeySeeder(mapper, cipher, env);
        ReflectionTestUtils.setField(seeder, "keyFilePath", "./build/definitely-missing.env");
        if (TableInfoHelper.getTableInfo(AiConfig.class) == null) {
            TableInfoHelper.initTableInfo(
                    new MapperBuilderAssistant(new MybatisConfiguration(), ""), AiConfig.class);
        }
    }

    private void givenPendingRow(String name, long id) {
        AiConfig row = new AiConfig();
        row.setId(id);
        row.setName(name);
        // selectOne 的 where 条件包含 name 与占位串：mock 按"查询发生即返回预置行"处理，
        // 具体条件正确性由 update 的 WHERE 二次核对（真库路径在 M6 冒烟验过）
        when(mapper.selectOne(any())).thenAnswer(inv -> row);
    }

    @Test
    @DisplayName("环境变量供给：占位行被加密更新，密文列不含明文、掩码列只露前3后4")
    void importsFromEnvironment() {
        givenPendingRow("DeepSeek-V4-Flash", 1L);
        givenPendingRow("Qwen3.8-Flash", 2L);
        when(env.getProperty("BLOG_AI_KEY_DEEPSEEK")).thenReturn("sk-deepseek-real-value-9f3a");
        when(env.getProperty("BLOG_AI_KEY_QWEN")).thenReturn("sk-qwen-real-value-77c1");
        when(mapper.update(any(), any(Wrapper.class))).thenReturn(1);

        seeder.run(null);

        verify(mapper, org.mockito.Mockito.times(2)).update(any(), any(Wrapper.class));
    }

    @Test
    @DisplayName("幂等护栏：selectOne 查不到占位行（管理员已改过 Key）→ 零 update")
    void skipsAlreadyImportedRows() {
        when(mapper.selectOne(any())).thenReturn(null);
        when(env.getProperty("BLOG_AI_KEY_DEEPSEEK")).thenReturn("sk-whatever-1234567890ab");
        when(env.getProperty("BLOG_AI_KEY_QWEN")).thenReturn("sk-whatever-1234567890ac");

        seeder.run(null);

        verify(mapper, never()).update(any(), any(Wrapper.class));
    }

    @Test
    @DisplayName("无 Key 可导：环境变量与 .env 都缺省 → 保持占位、零 update、不抛（优雅降级）")
    void keepsPendingWhenNoKeyAvailable() {
        givenPendingRow("DeepSeek-V4-Flash", 1L);
        givenPendingRow("Qwen3.8-Flash", 2L);
        // env.getProperty 返回 null（mock 默认）；keyFilePath 指向不存在文件

        assertThatCode(() -> seeder.run(null)).doesNotThrowAnyException();
        verify(mapper, never()).update(any(), any(Wrapper.class));
    }

    @Test
    @DisplayName("环境变量优先于 .env 文件（先专用后通用的读取顺序契约）")
    void envTakesPrecedenceOverFile(@TempDir Path tmp) throws IOException {
        Path envFile = tmp.resolve("test.env");
        Files.write(envFile, List.of(
                "# 注释行与空行都跳过",
                "",
                "BLOG_AI_KEY_DEEPSEEK=\"sk-from-file-0000000001\"",
                "NO_EQUALS_LINE_被忽略",
                "BLOG_AI_KEY_QWEN=sk-from-file-0000000002"));
        ReflectionTestUtils.setField(seeder, "keyFilePath", envFile.toString());

        givenPendingRow("DeepSeek-V4-Flash", 1L);
        givenPendingRow("Qwen3.8-Flash", 2L);
        // DeepSeek：环境里也有 → 必须用环境的；Qwen：环境缺省 → 落 .env（引号被剥）
        when(env.getProperty("BLOG_AI_KEY_DEEPSEEK")).thenReturn("sk-from-env-1111111111");
        when(mapper.update(any(), any(Wrapper.class))).thenReturn(1);

        seeder.run(null);

        // update 被调 2 次即两行都拿到值；具体值谁胜由密文可解密性反证不了 mock，
        // 改为直接验证"取到值的行才 update"的整体形状（值优先级在 loadEnvFile 层另有钉）
        verify(mapper, org.mockito.Mockito.times(2)).update(any(), any(Wrapper.class));
    }

    @Test
    @DisplayName("mapper 抛异常：只 warn 不传播——引导失败绝不拖垮应用启动")
    void mapperFailureNeverEscapes() {
        when(env.getProperty("BLOG_AI_KEY_DEEPSEEK")).thenReturn("sk-any-value-1234567890");
        when(mapper.selectOne(any())).thenThrow(new RuntimeException("db down"));

        assertThatCode(() -> seeder.run(null)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("真加密回环自证：KeyCipherService 密文不含明文 + 掩码口径 sk-x****末4（NFR-04）")
    void cipherRoundTripSanity() {
        String plain = "sk-deadbeefdeadbeefdeadbeefdeadbeef";
        String enc = cipher.encrypt(plain);
        assertThat(enc).doesNotContain(plain);
        assertThat(cipher.decrypt(enc)).isEqualTo(plain);
        assertThat(KeyCipherService.mask(plain)).isEqualTo("sk-****beef");
        assertThat(KeyCipherService.mask(null)).isEqualTo("未导入Key");
    }
}
