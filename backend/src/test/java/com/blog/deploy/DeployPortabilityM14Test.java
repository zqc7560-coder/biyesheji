package com.blog.deploy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M14 部署可移植性验收资产契约测试（纯文件断言，与 DeployAssetsM12Test 同族）。
 *
 * <p>M14 的交付物是「从零部署演练」+ 其机器可执行版 {@code deploy/scripts/portability-check.py}
 * 与 README §8 的演练记录。演练是一次性动作，但资产会腐化：脚本被改坏、README 步骤
 * 与 compose 漂移、.env.example 键与脚本读取的键不同名——这些都会让"下一台机器"
 * 照着文档部署时翻车。本测试把这些跨文件一致性钉成回归锁（M12/M13 已证明这套
 * "纯文件断言"策略能挡住真机才暴露的缺陷，如 healthcheck 变量注入）。
 *
 * <p>为什么不用 @ParameterizedTest 塞进 DeployAssetsM12Test：职责不同——那边锁
 * "部署资产自身正确"，这边锁"可移植性验收链路（文档⇄脚本⇄模板）三方一致"。
 */
class DeployPortabilityM14Test {

    /** 仓库根：与 DeployAssetsM12Test 相同的向上探测策略（本机/CI 双兼容） */
    private static final Path REPO_ROOT = findRepoRoot();

    private static Path findRepoRoot() {
        Path p = Path.of("").toAbsolutePath();
        for (int i = 0; i < 6 && p != null; i++, p = p.getParent()) {
            if (Files.exists(p.resolve("deploy").resolve("docker-compose.yml"))) {
                return p;
            }
        }
        throw new IllegalStateException("未能定位仓库根——deploy/docker-compose.yml 可能被移动");
    }

    private static String read(String relPath) {
        try {
            return Files.readString(REPO_ROOT.resolve(relPath), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("资产缺失或不可读: " + relPath, e);
        }
    }

    private static final String CHECK_SCRIPT = "deploy/scripts/portability-check.py";

    @Test
    @DisplayName("portability-check.py 存在且 Python 语法可编译（py_compile 真跑）")
    void checkScriptCompiles() throws Exception {
        Path script = REPO_ROOT.resolve(CHECK_SCRIPT);
        assertTrue(Files.exists(script), CHECK_SCRIPT + " 缺失——可移植性验收的机器可执行版是 M14 核心交付物");

        // 用运行时自身的解释器做纯语法编译（CI ubuntu-latest 的 python3 / 本机 python 皆在 PATH）。
        // 语法坏=目标机照 README 跑验收时直接 SyntaxError，这比部署失败更难排查（用户以为环境坏了）。
        // 刻意用 compile() 求值而非 py_compile：后者会在仓库里生成 __pycache__ 污染工作树
        // （M14 首跑实测：每次 mvn test 都在 deploy/scripts/ 落一个 .pyc，被 git add -A 顺手带走）。
        String snippet = "compile(open(r'%s', encoding='utf-8').read(), 'portability-check.py', 'exec')"
                + "; print('syntax-ok')";
        List<String> pythons = List.of("python", "python3", "py");
        String lastErr = "";
        for (String py : pythons) {
            try {
                Process p = new ProcessBuilder(py, "-c", String.format(snippet, script))
                        .redirectErrorStream(true).start();
                String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                if (p.waitFor() == 0 && out.contains("syntax-ok")) {
                    return; // 任一解释器编译通过即达成契约
                }
                lastErr = py + ": " + out;
            } catch (IOException e) {
                lastErr = py + " 不可用: " + e.getMessage();
            }
        }
        fail("所有候选解释器都无法编译验收脚本。" + lastErr);
    }

    @Test
    @DisplayName("验收脚本读取的 .env 键与 .env.example 模板逐一对应（漏键=目标机 cp 后脚本 FileNotFoundError）")
    void scriptEnvKeysMatchTemplate() {
        String script = read(CHECK_SCRIPT);
        // 脚本用 load_env("X") 读凭据；.env.example 是目标机 cp 的唯一来源，两边必须同名。
        Matcher m = Pattern.compile("load_env\\(\"(\\w+)\"\\)").matcher(script);
        int count = 0;
        while (m.find()) {
            count++;
            String key = m.group(1);
            assertTrue(Pattern.compile("^" + key + "=", Pattern.MULTILINE)
                            .matcher(read("deploy/.env.example")).find(),
                    ".env.example 缺少脚本依赖的键: " + key);
        }
        assertTrue(count >= 1, "脚本未通过 load_env 读取任何 .env 键——硬编码口令的形态必须被本测试拦下");
    }

    @Test
    @DisplayName("验收脚本红线自锁：零真实 Key 形态 + 禁止硬编码口令字面量（凭据只能来自 .env）")
    void checkScriptHasNoSecrets() {
        String script = read(CHECK_SCRIPT);
        // 与 DeployAssetsM12Test.secretRedLines 同口径：真实 Key 形态 = sk-+20 位
        assertFalse(Pattern.compile("sk-[a-zA-Z0-9]{20,}").matcher(script).find(),
                "验收脚本含真实 Key 形态——它是要进 git 的，红线同样适用");
        // 口令一律 load_env 读出，脚本里不许出现 BLOG_ADMIN_PASSWORD=值 / password="值" 这类字面量
        assertFalse(Pattern.compile("BLOG_ADMIN_PASSWORD\\s*=\\s*[\"'][^\"']+[\"']").matcher(script).find(),
                "验收脚本硬编码了管理员口令字面量");
    }

    @Test
    @DisplayName("验收脚本的断言骨架完整：10 项检查齐备、分页信封字段口径钉死为 list")
    void checkScriptCoversPortabilityClaims() {
        String script = read(CHECK_SCRIPT);
        // M14 台账承诺的验收面：静态入口/健康/自动初始化/登录/开箱即用/双厂商/卷直出/生产护栏。
        // 逐条按 "N 关键字" 形态扫 v.check(...) 注册行——项被删=承诺面缩水，必须在这里变红而不是在目标机上。
        for (String must : List.of("首页经 Nginx", "/api/health", "匿名文章列表", "admin 登录",
                "ai/status", "开箱即用", "AI 摘要端到端", "双厂商连通", "/uploads", "生产护栏")) {
            assertTrue(script.contains(must), "验收脚本缺少承诺断言: " + must);
        }
        // PageResult 防腐层的行集字段名是 list 不是 records（M14 演练第一手踩坑：
        // 按直觉写 records 会拿到 dict.keys() 当行集，AttributeError 假失败）。
        // 脚本必须读 ["list"]；哪天后端字段改名，这条与后端测试一起红，逼同步。
        assertTrue(script.contains("[\"list\"]"), "分页信封字段口径漂移：脚本应读 data[\"list\"]");
        // 掩码豁免逻辑必须在：sk-****183a 是设计允许的回显形态，一刀切 "sk-" 会误红
        assertTrue(script.contains("(?!\\\\*\\*\\*\\*)") || script.contains("(?!\\*\\*\\*\\*)"),
                "明文 Key 扫描必须豁免掩码形态 sk-****，否则第 6 项永远假红");
    }

    @Test
    @DisplayName("README §8 从零部署验证步骤：命令形态与 compose 文件一致、演练结论已登记")
    void readmeDrillSectionWired() {
        String readme = read("deploy/README.md");
        // §8 必须存在且给出"克隆→.env→构建→验收→收尾"的完整命令链
        assertTrue(readme.contains("## 8."), "deploy/README.md 缺少 §8 从零部署验证步骤（M14 核心交付）");
        int s8 = readme.indexOf("## 8.");
        String drill = readme.substring(s8);
        for (String must : List.of("git clone", ".env.example", "docker compose",
                "--build", "portability-check.py", "down -v")) {
            assertTrue(drill.contains(must), "§8 演练步骤缺少关键命令要素: " + must);
        }
        // 双文件 override 是 M14 演练实际采用的生产形态命令，README 不许退化成单文件
        assertTrue(drill.contains("docker-compose.prod.yml"), "§8 必须演练 prod override 双文件形态");
        // 真实结论登记（防"写了步骤没跑过"的口径造假——数字来自演练记录，改步骤不改结论会变红）
        assertNotNull(drill);
        assertTrue(Pattern.compile("(42|27|28|39|40|41|43|44|45)s?").matcher(drill).find(),
                "§8 未登记真实耗时（演练证据要求）");
    }

    private static void fail(String msg) {
        throw new AssertionError(msg);
    }
}
