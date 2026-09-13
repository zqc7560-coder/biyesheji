package com.blog.deploy;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M16 压测资产契约测试（纯文件断言，与 DeployAssetsM12/M14Test 同族）。
 *
 * <p>压测与部署不同：跑一次就出数字，但**资产腐化是静默的**——JMX 改坏一个属性名，
 * 场景参数会被无声忽略、全员用默认 0 线程"成功"跑出一个零样本报告；runner 与 JMX
 * 的属性键一旦漂移，压测结论就成了无源之水。本次 M16 首跑真实踩中三颗雷
 * （中文路径炸 JMeter 报告目录 / PRIME 缺省值没被 runner 收口导致每个场景烧 30 次
 * 真实模型调用 / MSYS 路径喂原生 python 解析成 D:\d\...），每一颗都本可以被这套
 * 文件断言提前挡住。教训：M12/M13 已反复证明"纯文件断言能挡住真机才暴露的缺陷"，
 * 压测资产同样适用。
 *
 * <p>断言分四层：
 * ① JMX 是良构 XML 且五类线程组（SETUP/PRIME/四场景）结构完整、线程数全部属性化；
 * ② runner ⇄ JMX 的 -J 属性键双向一致（跨文件契约，最易漂移处）；
 * ③ 数据资产完整：ai_bodies 30 个文件与 CSV 清单一一对应、纯 ASCII、标题全局唯一
 *    （缓存键唯一性的前置条件——ai_bodies 标题同时是 seed 文章的标题，单一事实源）；
 * ④ 工程卫生：runner 过 bash -n、产物/暂存目录默认值为纯 ASCII（中文路径回归锁）、
 *    全目录真实 Key 红线扫描。
 */
class LoadTestAssetsM16Test {

    /** 仓库根：与 M12/M14 测试相同的向上探测策略（本机/CI 双兼容） */
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

    private static final String DIR = "deploy/loadtest";
    private static final Path JMX = REPO_ROOT.resolve(DIR).resolve("blog-load-test.jmx");
    private static final Path RUNNER = REPO_ROOT.resolve(DIR).resolve("run-load-test.sh");

    private static String read(Path p) {
        try {
            return Files.readString(p, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("资产缺失或不可读: " + p, e);
        }
    }

    /** 解析 JMX 为 DOM（禁外部实体：测试的是自家文件，但按 XXE 卫生默认关） */
    private static Document jmxDom() throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        return f.newDocumentBuilder().parse(JMX.toFile());
    }

    /** 找指定 testname 的 ThreadGroup 元素 */
    private static Element threadGroup(Document doc, String testname) {
        NodeList tgs = doc.getElementsByTagName("ThreadGroup");
        for (int i = 0; i < tgs.getLength(); i++) {
            Element e = (Element) tgs.item(i);
            if (testname.equals(e.getAttribute("testname"))) {
                return e;
            }
        }
        return null;
    }

    // ==================== ① JMX 结构与属性化 ====================

    @Test
    @DisplayName("JMX 良构且五类线程组齐备（SETUP/PRIME/四场景在一张模板里）")
    void jmxHasAllThreadGroups() throws Exception {
        Document doc = jmxDom();
        for (String name : List.of("SETUP-login", "PRIME-ai-cache",
                "TG-LIST", "TG-DETAIL", "TG-AI", "TG-AI-DEGRADED")) {
            assertNotNull(threadGroup(doc, name), "缺线程组: " + name);
        }
    }

    @Test
    @DisplayName("场景线程数全部 __P 属性化且缺省 0——防止'忘传参烧全量'")
    void scenarioThreadsAreParameterizedWithZeroDefault() throws Exception {
        Document doc = jmxDom();
        // 四个场景组的 num_threads 必须是 ${__P(xxx_threads,0)}：缺省关闭，
        // 由 runner 显式开闸。首跑就靠这条挡掉"PRIME 缺省 30 全员烧模型"的同族问题。
        for (String pair : List.of("TG-LIST=list", "TG-DETAIL=detail",
                "TG-AI=ai", "TG-AI-DEGRADED=deg")) {
            String[] parts = pair.split("=");
            Element tg = threadGroup(doc, parts[0]);
            NodeList props = tg.getElementsByTagName("stringProp");
            String threads = null;
            for (int i = 0; i < props.getLength(); i++) {
                Element e = (Element) props.item(i);
                if ("ThreadGroup.num_threads".equals(e.getAttribute("name"))) {
                    threads = e.getTextContent();
                }
            }
            assertNotNull(threads, parts[0] + " 无 num_threads");
            assertTrue(threads.startsWith("${__P(" + parts[1] + "_threads,"),
                    parts[0] + " 线程数未走 " + parts[1] + "_threads 属性: " + threads);
            assertTrue(threads.endsWith(",0)}"),
                    parts[0] + " 线程数缺省必须为 0（缺省即开=事故）: " + threads);
        }
        // PRIME 循环数同样属性化（runner 对非 ai 场景显式置 0）
        String jmx = read(JMX);
        assertTrue(jmx.contains("${__P(prime_loops,30)}"),
                "PRIME loops 必须属性化，供 runner 在非 ai 场景收口为 0");
    }

    @Test
    @DisplayName("AI/降级组断言钉信封 code（200 空壳不算过）；降级组断 4004")
    void assertionsPinnedToEnvelope() throws Exception {
        Document doc = jmxDom();
        String xml = read(JMX);
        // 场景组全部带 "code":0 断言，降级组带 "code":4004——断言缺失=把错误率 0 的
        // 假象交给 JMeter 默认的"非 2xx 才算失败"，而本系统 BizException 全走 HTTP 200。
        assertTrue(xml.contains("&quot;code&quot;:0") || xml.contains("\"code\":0"),
                "正向场景必须断言信封 code=0");
        Element degTg = threadGroup(doc, "TG-AI-DEGRADED");
        assertNotNull(degTg);
        int idx = xml.indexOf("TG-AI-DEGRADED");
        assertTrue(xml.indexOf("4004", idx) > 0, "降级组必须断言 code=4004（闸门语义核验）");
    }

    // ==================== ② runner ⇄ JMX 跨文件属性契约 ====================

    @Test
    @DisplayName("runner 下发的 -J 属性在 JMX 侧全部有 __P 消费者（防静默失效）")
    void runnerPropsConsumedByJmx() throws Exception {
        String runner = read(RUNNER);
        String jmx = read(JMX);
        Set<String> produced = new HashSet<>();
        Matcher m = Pattern.compile("-J([A-Za-z_0-9]+)=").matcher(runner);
        while (m.find()) {
            produced.add(m.group(1));
        }
        assertFalse(produced.isEmpty(), "runner 未产出任何 -J 属性？");
        // SETUP token 由 JMX 内部 props 传递（ai_token 只在 JMX 消费、runner 不产出），
        // 所以只校验 runner 产出的键必须被 JMX 读到。
        // 消费形态有两种：① ${__P(key,...)}（线程组/采样器属性里的标准读法）；
        // ② Groovy 里 props.getProperty("key",...)（ai_body_dir 走这条——JSR223
        // 预处理器要拿它拼文件路径，__P 在脚本文本里不可用）。
        // M16 首跑只认①，把②误报成"属性无人消费"——两种形态都算合法接线。
        for (String key : produced) {
            assertTrue(jmx.contains("__P(" + key + ",") || jmx.contains("{" + key + "}")
                            || jmx.contains("getProperty(\"" + key + "\""),
                    "JMX 不消费 runner 下发的属性 " + key + "（改名漂移=参数静默丢失）");
        }
    }

    @Test
    @DisplayName("JMX 声明的场景属性键与 runner flag_common 的开合逻辑一一对应")
    void scenarioKeysAligned() {
        String jmx = read(JMX);
        String runner = read(RUNNER);
        for (String key : List.of("list_threads", "detail_threads", "ai_threads", "deg_threads")) {
            assertTrue(jmx.contains("__P(" + key), "JMX 缺场景属性: " + key);
            assertTrue(runner.contains("-J" + key), "runner 缺场景开关: " + key);
        }
    }

    // ==================== ③ 数据资产完整性（缓存键唯一性的地基） ====================

    @Test
    @DisplayName("ai_bodies 与 CSV 清单一一对应：30 文件、纯 ASCII、合法 JSON、标题全局唯一")
    void aiBodiesConsistentWithManifest() throws Exception {
        Path bodiesDir = REPO_ROOT.resolve(DIR).resolve("ai_bodies");
        List<String> csvLines = Files.readAllLines(
                REPO_ROOT.resolve(DIR).resolve("ai_bodies.csv"), StandardCharsets.US_ASCII);
        assertEquals("file", csvLines.get(0).trim(), "CSV 首行必须是表头 file（JMX ignoreFirstLine=true）");
        List<String> names = csvLines.subList(1, csvLines.size()).stream()
                .map(String::trim).filter(s -> !s.isEmpty()).toList();
        assertEquals(30, names.size(), "请求体数量必须等于压测文章数 30");
        Set<String> titles = new HashSet<>();
        for (String n : names) {
            Path f = bodiesDir.resolve(n);
            assertTrue(Files.exists(f), "清单引用缺失文件: " + n);
            byte[] raw = Files.readAllBytes(f);
            for (byte b : raw) {
                assertTrue(b >= 0x20 && b < 0x7F || b == '\n' || b == '\r',
                        n + " 含非 ASCII 字节——JMX 决策要求纯 ASCII 转义形态（Windows 编码免疫）");
            }
            var node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(raw);
            String title = node.get("title").asText();
            assertTrue(titles.add(title), "标题重复=缓存键坍缩风险: " + title);
            assertTrue(node.get("contentMd").asText().length() > 800,
                    n + " 正文过短，压测路径开销失真");
        }
        // 清单与目录互为镜像：不许有孤儿文件（seed 与 PRIME 都以 CSV 为准）
        try (Stream<Path> s = Files.list(bodiesDir)) {
            Set<String> onDisk = new HashSet<>();
            s.forEach(p -> onDisk.add(p.getFileName().toString()));
            assertEquals(new HashSet<>(names), onDisk, "ai_bodies 目录与 CSV 清单不一致");
        }
    }

    @Test
    @DisplayName("seed 脚本以 ai_bodies 的 title 为单一事实源（防两处独立维护漂移）")
    void seedReadsTitlesFromBodies() {
        String seed = read(REPO_ROOT.resolve(DIR).resolve("seed-loadtest-data.py"));
        assertTrue(seed.contains("ai_bodies"), "seed 必须从 ai_bodies/*.json 读标题");
        // 禁止的是"带字面量内容的标题"（两处独立定义=漂移之源）。
        // 精确匹配 "title": + 引号内容；发布请求体里的 "title": t 是变量引用，
        // 是"用从 ai_bodies 读来的标题"的合法形态，M16 首跑把它误判成独立拼标题。
        assertFalse(Pattern.compile("\"title\"\\s*:\\s*\"[^\"]+\"").matcher(seed).find(),
                "seed 里不得再独立拼标题字面量（两处定义=漂移之源）");
    }

    // ==================== ④ 工程卫生 ====================

    @Test
    @DisplayName("runner 过 bash -n 语法校验（复用 M12 的 git-bash 探测经验）")
    void runnerPassesBashSyntaxCheck() throws Exception {
        String bash = bashAvailable();
        Assumptions.assumeTrue(bash != null, "环境无可用 bash，跳过语法校验");
        Process p = new ProcessBuilder(bash, "-n", RUNNER.toString())
                .redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, p.waitFor(), "bash -n 失败: " + out);
    }

    @Test
    @DisplayName("产物/暂存目录默认值为纯 ASCII 路径——JMeter 报告写不进中文路径的回归锁")
    void outputDirsAreAsciiSafe() {
        String runner = read(RUNNER);
        Matcher m = Pattern.compile("OUT_ROOT=\\\"\\$\\{LOADTEST_OUT:-([^}]+)\\}").matcher(runner);
        assertTrue(m.find(), "runner 必须保留 OUT_ROOT 默认值声明");
        String outRoot = m.group(1);
        assertTrue(outRoot.chars().allMatch(c -> c < 128),
                "JMeter 报告目录缺省必须纯 ASCII（M16 实跑：中文路径下报告生成器直接罢工）: " + outRoot);
        m = Pattern.compile("STAGE_DIR=\\\"\\$\\{LOADTEST_STAGE:-([^}]+)\\}").matcher(runner);
        assertTrue(m.find(), "runner 必须保留 STAGE_DIR 暂存机制（MSYS 路径喂 jmeter.bat 必坏）");
        assertTrue(m.group(1).chars().allMatch(c -> c < 128), "暂存目录缺省必须纯 ASCII");
    }

    @Test
    @DisplayName("runner 不以 argv 传管理口令（进程列表明文泄露防线）")
    void runnerKeepsPasswordOffArgvForSubtools() {
        String runner = read(RUNNER);
        assertFalse(runner.contains("ai_switch.py\" --base \"${API_BASE}\" --password"),
                "ai_switch 子进程口令必须走环境变量注入，禁止 --password argv 形态");
        assertTrue(runner.contains("BLOG_ADMIN_PASSWORD=\"${ADMIN_PASS}\""),
                "降级注入调用点应使用环境变量形态传口令");
    }

    @Test
    @DisplayName("详情场景 id 区间经 runner 显式转发（seed 打印 id_min/id_max ⇄ -J 收口）")
    void detailIdRangeIsForwardedByRunner() {
        // M16 真实踩坑：compose 全新库 seed 出的文章是 id 1..30，而 JMX 缺省
        // 13..42 是开发库口径——不转发 -Jid_min/-Jid_max，详情场景 2/3 请求打
        // 幽灵 id 拿 404，jtl 数字看着正常但整场压测语义作废（且 404 不计数
        // 防污染的设计还会顺带让 PV 趋势失真）。runner 必须有显式收口+告警。
        String runner = read(RUNNER);
        assertTrue(runner.contains("-Jid_min=\"${ID_MIN}\"") && runner.contains("-Jid_max=\"${ID_MAX}\""),
                "runner 必须把 ID_MIN/ID_MAX 转发为 -Jid_min/-Jid_max");
        assertTrue(runner.contains("警告：未设 ID_MIN/ID_MAX"),
                "detail 场景缺 ID_MIN/ID_MAX 必须告警（静默用 JMX 缺省=404 幽灵文章）");
        // seed 脚本必须打印实际 id 区间（操作者据此设置 ID_MIN/ID_MAX，闭环）
        String seed = read(REPO_ROOT.resolve(DIR).resolve("seed-loadtest-data.py"));
        assertTrue(seed.contains("id_min=%d id_max=%d"),
                "seed 脚本必须打印 id_min/id_max，否则 runner 收口无源可依");
        // JMX 缺省值必须与"全新 compose 库 + seed 实跑"的 id 区间一致（1..30）。
        // 历史教训：缺省曾是开发库口径 13..42，手工单跑 detail 不带 -J 时
        // 2/3 请求打幽灵 id——数字看着正常但整场语义作废（404 路径性能+PV 不计数）。
        String jmx = read(JMX);
        assertTrue(jmx.contains("__P(id_min,1)") && jmx.contains("__P(id_max,30)"),
                "detail 轮播 id 缺省必须对齐 seed 全新库实录 1..30（runner 总转发，缺省只兜手工单跑）");
    }

    @Test
    @DisplayName("压测目录与 tools 下无任何真实 Key 形态（sk- 红线扫描）")
    void secretRedLines() throws Exception {
        List<Path> targets = new ArrayList<>();
        try (Stream<Path> s = Files.walk(REPO_ROOT.resolve(DIR))) {
            s.filter(Files::isRegularFile).forEach(targets::add);
        }
        try (Stream<Path> s = Files.walk(REPO_ROOT.resolve("deploy/tools"))) {
            s.filter(Files::isRegularFile).forEach(targets::add);
        }
        // ISO-8859-1 字节保真读取（M14 教训：UTF_8 读二进制会炸 MalformedInput）
        for (Path p : targets) {
            if (p.toString().endsWith(".jtl") || p.getParent().getFileName().toString().equals("output")) {
                continue; // 产物不入库不扫描
            }
            String content = new String(Files.readAllBytes(p), StandardCharsets.ISO_8859_1);
            Matcher m = Pattern.compile("sk-[A-Za-z0-9]{20,}").matcher(content);
            assertFalse(m.find(), "疑似真实 Key 落入压测资产: " + p);
        }
    }

    /** 与 DeployAssetsM12Test 同款 bash 探测：PATH 裸 bash 可能是 WSL 桩，需哨兵验证 */
    private static String bashAvailable() {
        for (String cand : List.of("bash", "sh")) {
            try {
                Process p = new ProcessBuilder(cand, "-c", "echo bash-ok")
                        .redirectErrorStream(true).start();
                String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                if (p.waitFor() == 0 && out.contains("bash-ok")) {
                    return cand;
                }
            } catch (Exception ignored) {
                // 候选不可用则试下一个
            }
        }
        return null;
    }
}
