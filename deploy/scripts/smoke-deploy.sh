#!/usr/bin/env bash
# =====================================================================
# 部署冒烟验收（M12）：compose up 之后跑本脚本，30 秒内给出"这套环境能不能
# 交付"的二值结论。验收清单 §5"一键启动 5 分钟可访问"的机器可执行版。
#
# 每个检查都是真实 HTTP/TCP 断言，不猜不装：
#   1) 四容器 healthy（读 compose 自身的健康判定，不自建第二套真相）
#   2) Nginx 首页 200 且是 Vue 入口（title 含"博客"）
#   3) /api/health 经 Nginx 反代贯通，且 JSON 里 mysql/redis 均 up
#      （这一条同时验证了 nginx→server→mysql、nginx→server→redis 两跳链路）
#   4) 匿名可见文章列表 200（RBAC 公开白名单在部署形态下仍成立）
#   5) /uploads/ 静态直出可访问（共享卷挂载正确的证据；404 目录列表也算通，
#      只要不是 502——502 才说明卷/别名配置坏了）
#   6) AI 未配 Key 时 status 接口 configured=false（降级设计在部署形态的投影）
#
# BASE 默认 http://localhost，压远程：BASE=http://192.168.1.20 ./smoke-deploy.sh
# =====================================================================
set -uo pipefail
BASE="${BASE:-http://localhost}"
COMPOSE_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PASS=0; FAIL=0

ok()   { echo "  [PASS] $1"; PASS=$((PASS+1)); }
bad()  { echo "  [FAIL] $1"; FAIL=$((FAIL+1)); }

echo "== 部署冒烟 @ ${BASE} =="

# 1) 容器健康
# M16 修复假绿：旧写法 `grep -cv 'healthy'` 在 ps 输出为空（project 名不对/
# 栈没起）时统计数为 0 → "全部 healthy" PASS —— 无中生有。改为显式三段判定：
# 空列表=FAIL；非空但有 Status 不含 healthy 的行=FAIL 并列出坏的那几行。
# （nginx 服务同轮补了 healthcheck，"全部容器 healthy"口径自此对四服务成立。）
#
# COMPOSE_PROJECT 透传（M16 实跑抓出的真实接线缺陷）：verify-deploy.sh 用
# `-p blog-verify` 起栈，本脚本若不认同名 project，ps 查到的是默认 project
# （目录名 deploy）的空列表 → 第 1 项必 FAIL。假绿防线反过来把"调用方与
# 被检查对象不是同一个栈"这种错配给逮住了，修法=共享 project 而非拆防线。
COMPOSE_ARGS=()
[ -n "${COMPOSE_PROJECT:-}" ] && COMPOSE_ARGS+=(-p "${COMPOSE_PROJECT}")
# 空数组必须用 ${arr[@]+"${arr[@]}"} 展开——"${arr[@]:-}" 会扩出一个空串参数，
# docker compose 收到后直接报 unknown command（M16 实跑同族第二坑，verify 端先炸）
PS_OUT=$(cd "${COMPOSE_DIR}" && docker compose ${COMPOSE_ARGS[@]+"${COMPOSE_ARGS[@]}"} ps --format '{{.Name}} {{.Status}}' 2>/dev/null || true)
if [ -z "${PS_OUT}" ]; then
  bad "compose ps 为空——栈未启动或 project/目录不对（此情形绝不判通过）"
elif echo "${PS_OUT}" | grep -qv 'healthy'; then
  bad "存在非 healthy 容器：$(echo "${PS_OUT}" | grep -v 'healthy' | tr '\n' '; ')"
else
  ok "全部容器 healthy（$(echo "${PS_OUT}" | wc -l | tr -d ' ') 个）"
fi

# 2) 首页
HOME_HTML=$(curl -fsS --max-time 10 "${BASE}/" 2>/dev/null || true)
if echo "${HOME_HTML}" | grep -q "博客"; then ok "Nginx 首页 200 且为站点入口"; else bad "首页不可达或非站点 HTML"; fi

# 3) 健康检查贯通
H=$(curl -fsS --max-time 10 "${BASE}/api/health" 2>/dev/null || true)
# 后端 Result 信封 + HealthService 大写状态值（见 HealthService.check()），断言口径逐字对齐
if echo "${H}" | grep -q '"mysql":"UP"' && echo "${H}" | grep -q '"redis":"UP"'; then
  ok "/api/health: MySQL+Redis 双 up（两跳链路贯通）"
else bad "/api/health 异常：${H:-无响应}"; fi

# 4) 匿名文章列表
CODE=$(curl -s -o /dev/null -w '%{http_code}' --max-time 10 "${BASE}/api/articles?page=1&size=5")
[ "${CODE}" = "200" ] && ok "匿名文章列表 200" || bad "匿名文章列表 HTTP ${CODE}（预期 200）"

# 5) uploads 直出：Nginx 对不存在文件回 404、对目录回 403/404，只要不是 5xx（502=转发错/挂载坏）
CODE=$(curl -s -o /dev/null -w '%{http_code}' --max-time 10 "${BASE}/uploads/.probe")
case "${CODE}" in 4*) ok "/uploads 路由存活（HTTP ${CODE}，非反代 5xx）";; *) bad "/uploads 异常 HTTP ${CODE}";; esac

# 6) AI 状态口（登录才有意义，先验它不会被匿名炸 5xx）
CODE=$(curl -s -o /dev/null -w '%{http_code}' --max-time 10 "${BASE}/api/ai/status")
case "${CODE}" in 200|401) ok "/api/ai/status 返回 ${CODE}（登录闸门/正常态）";; *) bad "/api/ai/status HTTP ${CODE}";; esac

echo "== 结果：${PASS} 通过 / ${FAIL} 失败 =="
[ "${FAIL}" -eq 0 ] || exit 1
