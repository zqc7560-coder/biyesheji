package com.blog.deploy;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M12 部署资产契约测试（纯文件断言，不依赖 Spring 容器与任何中间件）。
 *
 * <p>要解决的问题：Docker Compose / Nginx / Shell 脚本这类"部署即运行"的资产，
 * 一旦写坏，炸的时机是答辩现场的 {@code docker compose up}——离代码提交最远、
 * 排查成本最高的位置。本测试把部署文件当作一等公民代码来锁契约：
 * <ol>
 *   <li>YAML 可被严格解析（SnakeYAML——与 Spring Boot 读配置同一解析器，口径一致）；</li>
 *   <li>compose 引用的全部 ${ENV_VAR} 在 .env.example 里有模板（漏一个键=目标机启动即空值）；</li>
 *   <li>compose 挂载的仓库内路径真实存在（改目录结构而忘改 compose 是高频回归）；</li>
 *   <li>超时链跨文件单调（Axios 40s &gt; Nginx 60s... 见方法注释——三处数字分属三个文件，
 *       只有机械比对才能防"改了后端忘改 Nginx"）；</li>
 *   <li>密钥红线：部署资产全量扫描不得出现真实 Key 形态（sk-+20 位）；</li>
 *   <li>bash 语法可过 {@code bash -n}（Linux CI 与本机 git-bash 双环境可用）。</li>
 * </ol>
 *
 * <p>路径解析策略：从 surefire 工作目录（backend/）向上走找 {@code deploy/docker-compose.yml}，
 * 因此本机（D:\毕业设计\backend）与 CI（/home/runner/work/.../backend）都能定位仓库根。
 */
class DeployAssetsM12Test {

    /** 仓库根：向上探测含 deploy/docker-compose.yml 的目录 */
    private static final Path REPO_ROOT = findRepoRoot();

    private static Path findRepoRoot() {
        Path p = Path.of("").toAbsolutePath();
        for (int i = 0; i < 6 && p != null; i++, p = p.getParent()) {
            if (Files.exists(p.resolve("deploy").resolve("docker-compose.yml"))) {
                return p;
            }
        }
        throw new IllegalStateException("未能从 " + Path.of("").toAbsolutePath()
                + " 向上定位到 deploy/docker-compose.yml——部署资产可能被移动，测试与工作目录约定强关联");
    }

    /** 读仓库内文本文件（统一 UTF-8；部署资产含中文注释，编码漂移会让正则断言假绿/假红） */
    private static String read(String relPath) {
        try {
            return Files.readString(REPO_ROOT.resolve(relPath), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("部署资产缺失或不可读: " + relPath, e);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> loadYaml(String relPath) {
        try (var in = Files.newInputStream(REPO_ROOT.resolve(relPath))) {
            return (Map<String, Object>) new Yaml().load(in);
        } catch (IOException e) {
            throw new UncheckedIOException("YAML 解析失败: " + relPath, e);
        }
    }

    // =================================================================
    // 1. Compose 结构与挂载契约
    // =================================================================

    @Test
    @DisplayName("compose 可严格解析且四服务齐备；MySQL 启动参数锁死 ngram/utf8mb4/时区")
    void composeStructure() {
        Map<String, Object> root = loadYaml("deploy/docker-compose.yml");
        Map<String, Object> services = (Map<String, Object>) root.get("services");
        assertNotNull(services, "缺少 services 段");
        assertEquals(Set.of("mysql", "redis", "blog-server", "nginx"), services.keySet(),
                "服务拓扑必须与《部署与测试.md》§1.2 一致（四服务）");

        // ngram_token_size 建库后不可改（难点 D-9）——必须出现在 mysql command 里，
        // 这行丢了中文全文搜索会静默退化成"分词全挂"，只能重灌库发现
        Map<String, Object> mysql = (Map<String, Object>) services.get("mysql");
        String mysqlCmd = String.join(" ", flatten(mysql.get("command")));
        assertTrue(mysqlCmd.contains("ngram_token_size=2"), "MySQL 缺 ngram_token_size=2 启动参数");
        assertTrue(mysqlCmd.contains("utf8mb4"), "MySQL 缺 utf8mb4 字符集参数");

        // Redis 生产三件套：AOF、口令、内存上限（§1.4 容量基线）
        Map<String, Object> redis = (Map<String, Object>) services.get("redis");
        String redisCmd = String.join(" ", flatten(redis.get("command")));
        assertTrue(redisCmd.contains("appendonly yes"), "Redis 未开 AOF");
        assertTrue(redisCmd.contains("requirepass"), "Redis 未设口令");
        assertTrue(redisCmd.contains("maxmemory 256mb"), "Redis 缺 256mb 上限（§1.4 基线）");

        // 初始化 SQL 挂载：15 表 DDL + 幂等种子按文件名序执行（01/02 前缀）
        String mysqlVolumes = String.valueOf(mysql.get("volumes"));
        assertTrue(mysqlVolumes.contains("01_schema.sql"), "schema.sql 未挂载到 initdb");
        assertTrue(mysqlVolumes.contains("02_data.sql"), "data.sql 未挂载到 initdb");

        // 后端环境注入面：prod 化必需的键一个不能少（漏键=启动期 ${VAR} 解析炸，
        // 提前到测试期暴露）
        Map<String, Object> serverEnv =
                (Map<String, Object>) ((Map<String, Object>) services.get("blog-server")).get("environment");
        assertTrue(serverEnv.keySet().containsAll(List.of(
                        "SPRING_PROFILES_ACTIVE", "BLOG_DB_URL", "BLOG_DB_PASSWORD",
                        "BLOG_REDIS_HOST", "BLOG_REDIS_PASSWORD", "BLOG_JWT_SECRET",
                        "BLOG_AI_MASTER_KEY")),
                "blog-server 环境变量注入不完整");
        assertEquals("docker", serverEnv.get("SPRING_PROFILES_ACTIVE"),
                "compose 内必须走 docker profile（服务名互访）");
    }

    /** command 既可能写成字符串（>折叠）也可能写成列表——统一拉平成 token 列表 */
    private static List<String> flatten(Object command) {
        if (command instanceof List<?> list) {
            return list.stream().map(String::valueOf).collect(Collectors.toList());
        }
        return List.of(String.valueOf(command).trim().split("\\s+"));
    }

    @Test
    @DisplayName("compose 内所有仓库相对挂载路径与构建上下文真实存在")
    void composeMountPathsExist() {
        // compose 里的 ../backend/... 与 ../frontend/dist 是"构建产物↔运行时"的接缝，
        // 仓库重构后最容易烂掉的部分
        assertTrue(Files.exists(REPO_ROOT.resolve("backend/src/main/resources/db/mysql/schema.sql")));
        assertTrue(Files.exists(REPO_ROOT.resolve("backend/src/main/resources/db/mysql/data.sql")));
        assertTrue(Files.exists(REPO_ROOT.resolve("backend/Dockerfile")));
        assertTrue(Files.exists(REPO_ROOT.resolve("backend/.dockerignore")));
        assertTrue(Files.exists(REPO_ROOT.resolve("deploy/nginx/conf.d/blog.conf")));
        // 前端 dist 是构建产物（gitignore），只要求 vite 工程在——CI 里 build 后必有 dist
        assertTrue(Files.exists(REPO_ROOT.resolve("frontend/package.json")));
    }

    // =================================================================
    // 2. 环境变量模板完整性（compose ⇄ .env.example 双向契约）
    // =================================================================

    /** compose 里 ${VAR} / ${VAR:?} / ${VAR:-默认} 形态的变量名抽取 */
    private static final Pattern ENV_REF = Pattern.compile("\\$\\{([A-Z][A-Z0-9_]+)");

    @Test
    @DisplayName("compose 引用的每个环境变量在 .env.example 中都有键位模板")
    void envExampleCoversCompose() {
        String compose = read("deploy/docker-compose.yml");
        Set<String> required = ENV_REF.matcher(compose).results()
                .map(m -> m.group(1)).collect(Collectors.toSet());
        assertFalse(required.isEmpty(), "compose 未引用任何 ${VAR}——模板断言失去意义，检查是否被改成硬编码");

        String example = read("deploy/.env.example");
        for (String var : required) {
            assertTrue(Pattern.compile("^" + var + "=", Pattern.MULTILINE).matcher(example).find(),
                    ".env.example 缺少键: " + var + "（目标机 cp 后该变量为空，服务必然起不来）");
        }
        // $$ 转义的容器侧展开（健康检查里的 MYSQL_ROOT_PASSWORD 等）不算宿主模板义务，
        // 上面的正则天然只匹配 ${ 开头，$${ 形态第一个 $ 后紧跟 { 前还有 $ 不满足 \\$\\{ 的锚定
    }

    @Test
    @DisplayName("密钥红线：仓库根 .gitignore 覆盖 .env；部署资产零真实 Key 形态")
    void secretRedLines() {
        String gitignore = read(".gitignore");
        assertTrue(gitignore.contains("\n.env\n"), ".gitignore 必须保留 .env 规则（真实 Key 的家）");

        // 真实 Key 形态 = sk- 后接 20+ 位（任务书两把 Key 与一切抄自仓库的泄露样本同形态）。
        // 扫描整个 deploy/ 与 backend/Dockerfile*：任何命中都意味着密钥被提交进了部署面
        Pattern realKey = Pattern.compile("sk-[a-zA-Z0-9]{20,}");
        try (Stream<Path> paths = Files.walk(REPO_ROOT.resolve("deploy"))) {
            for (Path f : paths.filter(Files::isRegularFile).toList()) {
                String name = f.getFileName().toString();
                // 本机真实 .env（gitignored）允许存在，测试不检查它——它根本不该被 clone 出来
                if (name.equals(".env")) {
                    continue;
                }
                String content = Files.readString(f, StandardCharsets.UTF_8);
                assertFalse(realKey.matcher(content).find(),
                        "部署资产含真实 Key 形态: " + REPO_ROOT.relativize(f));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        assertFalse(realKey.matcher(read("backend/Dockerfile")).find(), "Dockerfile 含 Key");
    }

    // =================================================================
    // 3. Nginx 配置契约（§1.3 要点的机器版）
    // =================================================================

    @Test
    @DisplayName("blog.conf 关键指令齐备：6m 闸门 / X-Real-IP / SPA 兜底 / gzip / uploads 直出")
    void nginxContract() {
        String conf = read("deploy/nginx/conf.d/blog.conf");
        assertTrue(conf.contains("client_max_body_size 6m"), "上传闸门未与后端 6MB 对齐");
        assertTrue(conf.contains("X-Real-IP"), "缺真实 IP 透传（login_log 会全记容器网 IP）");
        assertTrue(conf.contains("X-Forwarded-For"), "缺转发链头");
        assertTrue(conf.contains("try_files $uri $uri/ /index.html"), "SPA history 路由兜底缺失");
        assertTrue(conf.contains("gzip on"), "传输压缩未开（WBS 1.7.3）");
        assertTrue(conf.contains("location /uploads"), "uploads 直出未配置");

        // 活跃配置区（剔注释行）里 443 必须保持注释状态——演示机默认无证书，
        // 误放开 listen 443 会让 nginx -t 直接失败拖垮整个 compose 栈
        String active = conf.lines()
                .filter(l -> !l.strip().startsWith("#"))
                .collect(Collectors.joining("\n"));
        assertFalse(active.contains("listen 443"), "443 段默认必须整体注释（§1.3 交付口径）");
    }

    @Test
    @DisplayName("超时链跨三文件单调：单次 HTTP 30s < Deferred 35s < Axios 40s < Nginx 60s")
    void timeoutChainMonotonic() {
        // 这条链的语义（《AI模块设计.md》§7.3）：任何一环被单独改动而破坏单调性，
        // 用户看到的就不是后端降级文案而是 502/挂圈——四个数字分居四个文件，
        // 靠人肉评审必漏，用测试锁死。抽取失败（正则脱靶）直接判红：
        // "数字还在但形态变了"同样是不稳定契约，逼维护者来同步本测试。
        long singleMs = extractMs(read("backend/src/main/java/com/blog/module/ai/client/OpenAiCompatClient.java"),
                "\\?\\s*(\\d[\\d_]*)\\s*:\\s*request\\.getTimeoutMs");           // 默认读超时 30_000
        long deferred = extractMs(read("backend/src/main/java/com/blog/module/ai/controller/AiController.java"),
                "DEFERRED_TIMEOUT_MS\\s*=\\s*(\\d[\\d_]*)");                     // 35_000
        long axios = extractMs(read("frontend/src/api/request.js"),
                "timeout:\\s*(\\d[\\d_]*)");                                     // 40000
        long nginxMs = extractMs(read("deploy/nginx/conf.d/blog.conf"),
                "proxy_read_timeout\\s+(\\d+)s") * 1000;                         // 60s

        assertTrue(singleMs > 0 && deferred > 0 && axios > 0 && nginxMs > 0,
                "超时链某环节抽取脱靶（single=" + singleMs + " deferred=" + deferred
                        + " axios=" + axios + " nginx=" + nginxMs + "）——源文件形态变更需同步本测试");
        assertTrue(deferred > singleMs, "DeferredResult 必须 > 单次 HTTP（否则重试没有预算）");
        assertTrue(axios > deferred, "Axios 必须 > Deferred 35s（否则前端先断，4002 降级文案到不了用户）");
        assertTrue(nginxMs > axios, "Nginx 读超时必须 > Axios（否则 Nginx 先 504，统一响应信封丢失）");
    }

    /** 取第一个捕获组的毫秒数（容忍 Java 下划线字面量 35_000 形态）；脱靶返回 -1 */
    private static long extractMs(String text, String regex) {
        Matcher m = Pattern.compile(regex).matcher(text);
        return m.find() ? Long.parseLong(m.group(1).replace("_", "")) : -1;
    }

    // =================================================================
    // 4. Dockerfile / .dockerignore 契约
    // =================================================================

    @Test
    @DisplayName("后端镜像多阶段构建；.dockerignore 挡死 .env 泄进镜像层")
    void dockerfileContract() {
        String dockerfile = read("backend/Dockerfile");
        long fromCount = dockerfile.lines().filter(l -> l.strip().startsWith("FROM ")).count();
        assertTrue(fromCount >= 2, "必须多阶段（构建 JRE 与运行 JRE 分离，设计 §1.3）");
        assertTrue(dockerfile.contains("USER bloguser"), "生产镜像必须非 root 运行");
        // 产物名与 pom finalName 的一致性：pom 改成 blog-server-0.1.0.jar 这类变化
        // 会让 COPY 层炸——用断言把"改名要三处同步"（pom/Dockerfile/compose 无关）钉死
        String pom = read("backend/pom.xml");
        assertTrue(pom.contains("<finalName>blog-server</finalName>")
                        || dockerfile.matches("(?s).*cp target/.*blog-server.*"),
                "jar 产物名契约破坏（pom finalName 与 Dockerfile COPY 不一致）");

        String ignore = read("backend/.dockerignore");
        assertTrue(ignore.contains(".env"), ".env 未排除出构建上下文——真实 Key 有进镜像层风险");
        assertTrue(ignore.contains("target/"), "target/ 未排除：构建上下文肥大且带本机编译产物");
    }

    @Test
    @DisplayName("覆盖率门禁数据接线：gate 执行显式传 dataFile（M12 假绿修复回归锁）")
    void coverageGateDataFileWired() {
        // M12 实锤的基建 bug：jacoco check 目标不认插件属性 jacoco.dataFile，
        // 默认恒读 target/jacoco.exec——Windows 下 exec 被 profile 重定向到 E 盘后，
        // check"Skipping due to missing execution data file"静默放行=门禁假绿
        // （Linux CI 上 exec 存在所以会真判，两端行为分裂最难查）。
        // 断言 pom：gate execution 必须有显式 <dataFile>，且 properties 里有
        // 平台默认值兜底（Linux 上 ${jacoco.dataFile} 未定义会传未解析字面量）。
        String pom = read("backend/pom.xml");
        assertTrue(pom.contains("<dataFile>${jacoco.dataFile}</dataFile>"),
                "coverage-gate 的 check 执行缺显式 dataFile——补回去，否则本地门禁假绿");
        assertTrue(pom.matches("(?s).*<jacoco\\.dataFile>\\$\\{project\\.build\\.directory}.*"),
                "properties 缺 jacoco.dataFile 的平台默认值（Linux profile 不激活时引用悬空）");
    }

    // =================================================================
    // 5. Shell 脚本可执行性（语法级）
    // =================================================================

    @Test
    @DisplayName("三个部署脚本通过 bash -n 语法校验")
    void shellScriptsParse() throws Exception {
        String bash = bashAvailable();
        Assumptions.assumeTrue(bash != null, "环境无可用 bash（裸 Windows CI 不常见），跳过语法校验");
        for (String rel : List.of("deploy/scripts/backup-mysql.sh",
                "deploy/scripts/restore-mysql.sh", "deploy/scripts/smoke-deploy.sh")) {
            Process p = new ProcessBuilder(bash, "-n",
                    REPO_ROOT.resolve(rel).toString()).redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertEquals(0, p.waitFor(), rel + " 语法错误: " + out);
        }
    }

    /**
     * 定位可用 bash：本机裸 `bash` 命中的是 Windows 自带的 WSL 桩（未装发行版时
     * rc=1 输出乱码，M12 实测），不能当校验器。策略：先探 PATH 里的 bash 是否真可用，
     * 不可用则从 `where git` 的 Git 安装目录推导 usr/bin/bash.exe（git-bash 与 git
     * 必然同装同盘——本机 Git 在 E 盘，零 C 盘假设）。全部落空返回 null 触发跳过。
     */
    private static String bashAvailable() {
        for (String cand : bashCandidates()) {
            try {
                Process p = new ProcessBuilder(cand, "-c", "echo bash-ok")
                        .redirectErrorStream(true).start();
                String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                if (p.waitFor() == 0 && out.contains("bash-ok")) {
                    return cand;
                }
            } catch (Exception ignored) {
                // 候选不可用继续下一个
            }
        }
        return null;
    }

    private static List<String> bashCandidates() {
        List<String> list = new java.util.ArrayList<>(List.of("bash", "sh"));
        try {
            Process w = new ProcessBuilder("where", "git").start();
            String where = new String(w.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            for (String line : where.split("\\R")) {
                String l = line.trim();
                if (l.toLowerCase().endsWith("cmd\\git.exe")) {   // E:\develop\Git\Git\cmd\git.exe
                    list.add(0, l.substring(0, l.length() - "cmd\\git.exe".length())
                            + "usr\\bin\\bash.exe");              // 同根的 git-bash
                }
            }
        } catch (Exception ignored) {
        }
        return list;
    }
}
