# -*- coding: utf-8 -*-
"""
M16 压测数据种子（开发辅助脚本，不属于运行时）。

向目标环境灌 30 篇「压测样本文章」（发布态、真实长度中文正文+代码块），
标题与 ai_bodies/ai_*.json 的 title 逐字一致 —— 这样"文章"与"AI 请求体"
是同一批内容的两个投影：详情场景轮播的 id、AI 场景暖缓存的键，一一对应。

幂等性：先按标题精确查库，只补差额，重复执行安全（压测跑多轮常要重建环境）。
口令红线：管理员口令从 deploy/.env（已 gitignore）运行时读取，本文件零明文；
--base 可指向任意环境（本机 8080 直连 或 Nginx 80 口）。

用法：
  python seed-loadtest-data.py                       # 默认 http://127.0.0.1:8080
  python seed-loadtest-data.py --base http://localhost
  # 远端环境无 .env 时显式传口令（注意命令行会被进程列表窥视，优先用 --env-file）：
  python seed-loadtest-data.py --base http://x.x.x.x --password <pw> --env-file /path/.env>
"""
import argparse
import json
import os
import sys
import urllib.error
import urllib.parse
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
BODY_COUNT = 30  # 与 generate-ai-bodies.py 一致

LOREM = ("这是一个用于性能测试的中文段落，涵盖缓存命中、索引选择、并发控制与降级策略等主题，"
         "内容长度刻意接近真实技术文章，以便压力测试的数据分布不失真。")


def read_env(path):
    d = {}
    if os.path.exists(path):
        for line in open(path, encoding="utf-8"):
            line = line.strip()
            if line and not line.startswith("#") and "=" in line:
                k, v = line.split("=", 1)
                d[k] = v.strip()
    return d


def api(base, path, body=None, token=None, method=None):
    """HTTP 门面：UTF-8 显式编解码（M8 台账教训：cmd/PowerShell 传中文必坏，
    python urllib 直发才稳）。返回 (http_code, envelope_dict)。"""
    data = json.dumps(body).encode("utf-8") if body is not None else None
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = "Bearer " + token
    req = urllib.request.Request(base + path, data=data, headers=headers,
                                 method=method or ("POST" if body is not None else "GET"))
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            return r.status, json.loads(r.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        raw = e.read().decode("utf-8", errors="replace")
        try:
            return e.code, json.loads(raw)
        except json.JSONDecodeError:
            return e.code, {"message": raw[:200]}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", default="http://127.0.0.1:8080")
    ap.add_argument("--user", default="admin")
    ap.add_argument("--password", default=None,
                    help="缺省时从 --env-file 的 BLOG_ADMIN_PASSWORD 读")
    ap.add_argument("--env-file", default=os.path.normpath(
        os.path.join(HERE, "..", ".env")))
    ap.add_argument("--count", type=int, default=BODY_COUNT)
    args = ap.parse_args()

    pwd = args.password or read_env(args.env_file).get("BLOG_ADMIN_PASSWORD")
    if not pwd:
        sys.exit("无法取得管理员口令：传 --password 或保证 --env-file 里有 BLOG_ADMIN_PASSWORD")

    code, env = api(args.base, "/api/auth/login",
                    {"username": args.user, "password": pwd})
    if env.get("code") != 0:
        sys.exit("登录失败 http=%s %s" % (code, env))
    token = env["data"]["token"]

    # 标题清单与 AI 请求体同源：读 ai_bodies/ai_*.json 的 title（零重复定义）
    titles = []
    for i in range(args.count):
        with open(os.path.join(HERE, "ai_bodies", "ai_%02d.json" % i),
                  encoding="ascii") as f:
            titles.append(json.load(f)["title"])

    # 幂等核对：搜索精确标题只回完全匹配（M16 冒烟实测口径），缺哪篇补哪篇
    existing = {}
    for t in titles:
        _, r = api(args.base, "/api/articles?page=1&size=1&keyword=" + urllib.parse.quote(t))
        if r.get("code") == 0 and r["data"]["list"]:
            a = r["data"]["list"][0]
            if a["title"] == t:
                existing[t] = a["id"]
    missing = [t for t in titles if t not in existing]

    content = ("## 缓存与并发\n\n" + LOREM * 8 +
               "\n\n```java\npublic class Sample {\n  public static void main(String[] a) {\n"
               "    System.out.println(\"m16 load test\");\n  }\n}\n```\n\n" + LOREM * 8)

    for i, t in enumerate(missing):
        code, r = api(args.base, "/api/articles",
                      {"title": t, "contentMd": content,
                       "summary": "面向 JMeter 场景的压测样本：覆盖列表扫描、详情回源与缓存命中路径。",
                       "categoryId": 1, "status": 1}, token)
        if r.get("code") != 0:
            sys.exit("发布失败 [%s] %s" % (t, r))
        existing[t] = r["data"]["id"]

    ids = sorted(existing[t] for t in titles)
    print("seeded=%d existing=%d total=%d" % (len(missing), len(titles) - len(missing), len(ids)))
    print("id_min=%d id_max=%d" % (ids[0], ids[-1]))


if __name__ == "__main__":
    main()
