package com.blog.module.ai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M11 测试完善 · 标签解析器 20 组真实响应样本集（《部署与测试.md》§2 单测范围明示项：
 * "标签解析器（20 组真实响应样本）"，《AI模块设计.md》§5.2 三级防御解析的回归锁）。
 *
 * <p>样本来源与构造原则：S01~S13 覆盖开发期观察到的全部真实输出形态（契约内直出、
 * DeepSeek 带围栏、DashScope 带解释文字、纯散文退化切分等"违约"形态），S14~S20 针对
 * 合法化闸门逐条构造（停用词/长度双语境/大小写去重/数量截断/拒答话术/引号去壳）。
 * 每个样本都断言到**具体的期望标签序列**——解析器的价值是确定性收敛，期望写成
 * "非空即可"就失去了回归锁的意义（改一条闸门规则必须在这里留下可见的期望变化）。
 *
 * <p>为什么是纯 new 对象而不是 @SpringBootTest：ResultParserService 只依赖
 * ObjectMapper（无状态），容器化会把上下文启动成本摊进每个毫秒级断言；JaCoCo
 * 对两类测试的覆盖率统计等价。接口级管道行为已由 AiApiM6/M7Test 覆盖，本类专钉
 * 解析纯函数的样本面——两层互补不重复。
 */
@DisplayName("M11 标签解析器: 20组真实响应样本回归集")
class TagParserSamplesM11Test {

    /** 被测对象：与生产装配一致（构造器注入 ObjectMapper，本类只走 readValue 纯 API） */
    private final ResultParserService parser = new ResultParserService(new ObjectMapper());

    // ==================== 20 组样本：模型原始输出 → 期望标签序列 ====================

    static Stream<Arguments> tagSamples() {
        return Stream.of(
            // --- 契约内输出（容错②直通） ---
            Arguments.of("S01 标准JSON数组直出",
                    "[\"SpringBoot\",\"缓存\",\"Redis\"]",
                    List.of("SpringBoot", "缓存", "Redis")),
            Arguments.of("S02 数组内含空格排版（合法JSON直接解析）",
                    "[ \"微服务\", \"网关\" , \"限流\" ]",
                    List.of("微服务", "网关", "限流")),
            Arguments.of("S03 中英混排技术标签",
                    "[\"深度学习\",\"Transformer\",\"注意力机制\"]",
                    List.of("深度学习", "Transformer", "注意力机制")),
            Arguments.of("S04 数字元素转文本（Jackson把1024解析成Integer，转字符串后过长度闸）",
                    "[\"HTTP\", 1024, \"协议\"]",
                    List.of("HTTP", "1024", "协议")),

            // --- 容错①：代码围栏（DeepSeek 最常见违约形态） ---
            Arguments.of("S05 ```json围栏包裹",
                    "```json\n[\"消息队列\",\"Kafka\",\"分区\"]\n```",
                    List.of("消息队列", "Kafka", "分区")),
            Arguments.of("S06 无语言标记围栏",
                    "```\n[\"分布式事务\",\"Saga模式\"]\n```",
                    List.of("分布式事务", "Saga模式")),

            // --- 容错②救场：数组前后混解释文字（"掐头去尾定位[ … ]"的靶样本） ---
            Arguments.of("S07 前缀话术+数组",
                    "以下是为这篇文章生成的标签：[\"容器化\",\"Docker\",\"编排\"]",
                    List.of("容器化", "Docker", "编排")),
            Arguments.of("S08 前后缀话术夹数组",
                    "希望有帮助。[\"数据库索引\",\"B+树\"] 如需调整请告诉我。",
                    List.of("数据库索引", "B+树")),
            Arguments.of("S09 围栏内还套解释文字（容错①②串联）",
                    "```json\n标签如下：\n[\"API设计\",\"幂等性\"]\n```",
                    List.of("API设计", "幂等性")),

            // --- 容错③：彻底无 JSON 结构，退化切分 ---
            Arguments.of("S10 顿号分隔人类写法",
                    "微服务、熔断、降级",
                    List.of("微服务", "熔断", "降级")),
            Arguments.of("S11 中英逗号混用+「标签：」键值前缀剥离",
                    "标签：缓存穿透, 布隆过滤器，缓存雪崩",
                    List.of("缓存穿透", "布隆过滤器", "缓存雪崩")),
            Arguments.of("S12 首行Markdown列表符剥离（^锚定只作用于文本开头——行中'- '形态含空白，被空白闸门弃）",
                    "- 前端工程化、Vite、构建优化",
                    List.of("前端工程化", "Vite", "构建优化")),
            Arguments.of("S13 英文逗号切分（含空格的词组被空白闸门弃——提示词明令禁空格，服务端自证）",
                    "Kubernetes, Service Mesh, Istio",
                    List.of("Kubernetes", "Istio")),

            // --- 合法化闸门：停用词黑名单（提示词第3条的服务端执法） ---
            Arguments.of("S14 停用词与正常词混排，停用词逐一剔除",
                    "[\"分享\",\"Java\",\"笔记\",\"记录\",\"并发编程\"]",
                    List.of("Java", "并发编程")),

            // --- 合法化闸门：长度双语境（CJK 2~8 / ASCII 2~24）三连同毙 ---
            Arguments.of("S15 中文>8毙、ASCII>24毙、单字<2毙（全越界即全弃）",
                    "[\"这是一条明显超过八个中文字符长度的候选标签\",\"ABCDEFGHIJKLMNOPQRSTUVWXYZ1234567890\",\"短\"]",
                    List.of()),
            Arguments.of("S16 ASCII技术名10~24字符存活（设计文档示例SpringBoot口径的放宽）",
                    "[\"KubernetesInPractice\",\"SpringBoot\"]",
                    List.of("KubernetesInPractice", "SpringBoot")),

            // --- 合法化闸门：忽略大小写去重（镜像 MySQL utf8mb4_general_ci 唯一键） ---
            Arguments.of("S17 大小写变体只留首个原始形态",
                    "[\"Redis\",\"redis\",\"REDIS\",\"MySQL\"]",
                    List.of("Redis", "MySQL")),

            // --- 合法化闸门：数量截断 ≤6（前端勾选区不炸版） ---
            Arguments.of("S18 8个合法标签截断为前6个",
                    "[\"标签一二\",\"标签三四\",\"标签五六\",\"标签七八\",\"标签九十\",\"标签十一\",\"标签十二\",\"标签十三\"]",
                    List.of("标签一二", "标签三四", "标签五六", "标签七八", "标签九十", "标签十一")),

            // --- 拒答闸门：无JSON结构且含道歉/推脱词 → 整段弃（宁空勿污，调用方转4005） ---
            Arguments.of("S19 拒答话术散文（退化切分会洗出躲过长度闸的句子碎片，必须整段拒）",
                    "抱歉，这篇文章内容太少，我无法根据它生成有意义的标签，请提供更多上下文。",
                    List.of()),

            // --- 去壳：中文引号/书名号/尾随标点（先去壳再判长度，否则带壳超长误杀） ---
            Arguments.of("S20 全角引号壳+句号残渣的顿号文本",
                    "“消息中间件”。、『RabbitMQ』、\"RocketMQ\"",
                    List.of("消息中间件", "RabbitMQ", "RocketMQ"))
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("tagSamples")
    @DisplayName("样本组：模型原始输出 → 确定性标签序列")
    void sampleConvergence(String name, String raw, List<String> expected) {
        assertThat(parser.parseTags(raw)).isEqualTo(expected);
    }

    // ==================== 表外补充：解析器整体性质的钉死 ====================

    @Test
    @DisplayName("空/null/纯空白输入不抛异常，统一回空列表（调用方据此转4005）")
    void blankInputsAreSafe() {
        assertThat(parser.parseTags(null)).isEmpty();
        assertThat(parser.parseTags("")).isEmpty();
        assertThat(parser.parseTags("   \n  ")).isEmpty();
    }

    @Test
    @DisplayName("emoji与纯符号残渣剔除：提示词禁令的服务端自证（答辩可演示）")
    void symbolGarbageDropped() {
        assertThat(parser.parseTags("[\"🚀\",\"……\",\"—\",\"有效标签\"]"))
                .containsExactly("有效标签");
    }

    @Test
    @DisplayName("含'无法'字样的真实标签词不被拒答闸门误伤（闸门只在无JSON结构时启用）")
    void refusalGateOnlyForProse() {
        // JSON 结构存在时走容错②直出，REFUSAL 词面不参与判定——含"无法"二字的标签词存活
        assertThat(parser.parseTags("[\"无障碍设计\",\"无法访问\"]"))
                .containsExactly("无障碍设计", "无法访问");
    }

    @Test
    @DisplayName("散文里没有拒答词时仍走退化切分（拒答闸门不是'散文一刀切'）")
    void proseWithoutRefusalStillSplits() {
        // 无结构化意图的正常散文（模型"忘了"套数组）：>8 字的引导句被长度闸弃，
        // 逗号/顿号后的真词存活——拒答闸门只毙"带道歉话术"的，不毙"没格式"的
        assertThat(parser.parseTags("这篇文章讲的是Go语言、并发模型、调度器。"))
                .containsExactly("并发模型", "调度器");
    }

    @Test
    @DisplayName("嵌套对象/数组元素=解析失败（模型理解的'标签'不是标量时整数组弃，转下一级容错）")
    void nestedStructureFailsJsonLevel() {
        // 数组里有对象 → tryParseJsonArray 判 null；又无拒答词 → 退化切分按逗号碎切，
        // 切出的 "[{"name":"A"" 等碎片带 JSON 结构字符，被 normalizeTag 的结构字符闸门
        // 逐一毙掉——最终空收敛，绝不把 {"name":...} 形态入库
        assertThat(parser.parseTags("[{\"name\":\"A\",\"weight\":3}]")).isEmpty();
        // 反向钉子：结构字符闸门不许误杀真实技术标签形态（C++/Rust 带符号但无 {}[]）
        assertThat(parser.parseTags("[\"C++\",\"Rust\",\"Go\"]"))
                .containsExactly("C++", "Rust", "Go");
    }
}
