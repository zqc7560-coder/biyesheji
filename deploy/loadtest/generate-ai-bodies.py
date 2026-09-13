# -*- coding: utf-8 -*-
"""
M16 压测数据生成器（开发辅助脚本，不属于运行时）。

生成 deploy/loadtest/ai_bodies/ 下 30 个 AI 摘要请求体 + ai_bodies.csv 清单，
与 blog-load-test.jmx 的 PRIME/TG-AI/TG-AI-DEGRADED 三组配套。

为什么预生成静态文件而不是压测时现拼字符串：
  1. AI 缓存键 = SHA-256(标题 + "\\n" + 归一化正文)（AiCacheService.contentHash），
     PRIME 组按清单顺序暖缓存、TG-AI 组随机取同一批文件——两边字节级一致，
     缓存命中才可复现。运行时拼装极易因空白/编码差异导致键漂移、命中率失真。
  2. ensure_ascii=True 输出纯 ASCII（中文全部 \\u 转义）：Windows 下 JVM 的
     file.encoding 与 JMeter 读文件编码不可靠（M16 决策依据），转义形态免疫。
  3. 正文刻意接近真实文章长度（数千字），让归一化/截断/哈希走真实开销路径。

用法（可选复现，产物已入库无需重跑）：
  python generate-ai-bodies.py            # 重新生成 ai_bodies/ 与 ai_bodies.csv
"""
import hashlib
import json
import os

HERE = os.path.dirname(os.path.abspath(__file__))
OUT_DIR = os.path.join(HERE, "ai_bodies")
CSV_PATH = os.path.join(HERE, "ai_bodies.csv")
N = 30  # 与压测种子文章数一致（seed-loadtest-data.py）

# 与 seed-loadtest-data.py 的文章正文同源：段落重复构成数千字真实长度
LOREM = ("这是一个用于性能测试的中文段落，涵盖缓存命中、索引选择、并发控制与降级策略等主题，"
         "内容长度刻意接近真实技术文章，以便压力测试的数据分布不失真。")


def main():
    os.makedirs(OUT_DIR, exist_ok=True)
    names = []
    digests = set()
    for i in range(N):
        # 每篇带唯一差异段：确保 30 个缓存键互不相同（同键坍缩会让命中率虚高）
        body = {
            "title": "压测样本文章 第%02d篇 缓存与并发性能" % (i + 1),
            "contentMd": (
                "## 缓存与并发\n\n" + LOREM * 4 +
                "\n\n### 第%d篇专属段落\n\n本段是样本 %d 的差异内容：性能分析要区分 P50、P95 "
                "与 P99 三个分位，缓存命中路径的开销主要来自 Redis 往返与 JSON 序列化，"
                "哈希指纹 %s 保证该样本的缓存键全局唯一。\n\n" % (i + 1, i + 1,
                    hashlib.sha256(("m16-sample-%d" % i).encode()).hexdigest()[:16]) +
                "```java\npublic class Sample%02d {\n    public static void main(String[] args) {\n"
                "        System.out.println(\"load-test body %d\");\n    }\n}\n```\n" % (i + 1, i + 1)
                + LOREM * 4
            ),
            "style": "zh",
        }
        text = json.dumps(body, ensure_ascii=True, separators=(",", ":"))
        h = hashlib.md5(text.encode()).hexdigest()
        if h in digests:
            raise SystemExit("样本内容意外重复，缓存键会坍缩: %d" % i)
        digests.add(h)
        fname = "ai_%02d.json" % i
        names.append(fname)
        with open(os.path.join(OUT_DIR, fname), "w", encoding="ascii") as f:
            f.write(text)
    with open(CSV_PATH, "w", encoding="ascii", newline="\n") as f:
        f.write("file\n")
        for n_ in names:
            f.write(n_ + "\n")
    print("generated %d bodies -> %s" % (N, OUT_DIR))


if __name__ == "__main__":
    main()
