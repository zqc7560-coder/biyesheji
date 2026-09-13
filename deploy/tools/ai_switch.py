# -*- coding: utf-8 -*-
"""
M16 压测配套：AI 总开关（sys_config.ai_enabled）的 API 形态切换器。

为什么走 API 而不是直连 MySQL UPDATE：
  1. ai_enabled 在 SysConfigService.EDITABLE_KEYS 白名单里（M8），PUT 口自带
     类型校验 + 修改口双删缓存镜像（M9）——直接 UPDATE 会留下 Redis 旧镜像，
     AI 闸门（AiSysConfigReader 直查 DB，刻意不走镜像）不受影响，但语义上
     绕过了自己的治理面，演示时不可复制。
  2. 口令从命令行/环境传入，不落任何入库文件；本脚本自身零凭据。

用法：
  python ai_switch.py --base http://127.0.0.1:8080 --value false
  python ai_switch.py ... --password <pw>   # 或设环境变量 BLOG_ADMIN_PASSWORD

输出（stdout 单行，供 runner 判定）：OK code=0 或 FAIL <envelope>
"""
import argparse
import json
import os
import sys
import urllib.error
import urllib.request


def api(base, path, payload=None, token=None, method=None):
    data = json.dumps(payload).encode("utf-8") if payload is not None else None
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = "Bearer " + token
    req = urllib.request.Request(base + path, data=data, headers=headers,
                                 method=method or ("POST" if payload else "GET"))
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
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
    ap.add_argument("--password", default=os.environ.get("BLOG_ADMIN_PASSWORD", ""))
    ap.add_argument("--value", required=True, choices=["true", "false"])
    args = ap.parse_args()
    if not args.password:
        print("FAIL no-password (pass --password or env BLOG_ADMIN_PASSWORD)")
        sys.exit(2)
    _, login = api(args.base, "/api/auth/login",
                   {"username": args.user, "password": args.password})
    if login.get("code") != 0:
        print("FAIL login %s" % json.dumps(login, ensure_ascii=False)[:200])
        sys.exit(1)
    tok = login["data"]["token"]
    code, r = api(args.base, "/api/admin/system/config",
                  {"items": [{"key": "ai_enabled", "value": args.value}]},
                  token=tok, method="PUT")
    if r.get("code") == 0:
        # 复核读回：PUT 返回 0 不代表库里就是目标值（两阶段结构下应一致，但压测
        # 的注入前提必须"实证"而非"相信"）
        _, chk = api(args.base, "/api/admin/system/config", token=tok)
        row = next((x for x in (chk.get("data") or []) if x.get("key") == "ai_enabled"), None)
        if row and str(row.get("value")) == args.value:
            print("OK code=0 verified=%s" % args.value)
            return
        print("FAIL verify-readback %s" % row)
        sys.exit(1)
    print("FAIL put http=%s %s" % (code, json.dumps(r, ensure_ascii=False)[:200]))
    sys.exit(1)


if __name__ == "__main__":
    main()
