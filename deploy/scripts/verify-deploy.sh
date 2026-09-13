#!/usr/bin/env bash
# =====================================================================
# 交付验收入口（M16 交付物③）：把"compose up → 冒烟 → 可移植性验收"
# 收敛成一条命令，让答辩前自查与 CI 手动门禁共用同一个真相源。
#
# 为什么需要它（而不是让文档写三条命令）：
#   smoke-deploy.sh 与 portability-check.py 各自能独立跑，但两段之间的
#   "等待四服务 healthy"是手工步骤——上一轮真机演练里，redis 健康检查
#   变量未注入导致 blog-server 被 depends_on 门禁卡住 5 分钟不起，
#   当时是"跑冒烟→全 FAIL→回头查 docker compose ps"才知道根因。
#   本脚本把等待与失败诊断（自动 dump 非 healthy 容器的日志尾部）内置，
#   任何人一条命令就能看到"卡在哪、为什么卡"。
#
# 用法：
#   cd deploy && bash scripts/verify-deploy.sh              # 完整（含真实 AI 调用）
#   SKIP_AI=1 bash scripts/verify-deploy.sh                 # 离线机：跳过厂商连通
#   COMPOSE_PROJECT=m16 bash scripts/verify-deploy.sh       # 并行验证时区分队列
#   KEEP_UP=1 bash scripts/verify-deploy.sh                 # 验完不停栈（继续压测）
#
# 退出码：0=全部验收通过；1=任一步失败（输出里指明是哪一步）。
# 注意：本脚本只做"起→验→收"，不构建镜像以外的准备（前端 dist 需先
# npm run build——compose 里 nginx 直接挂载宿主机 dist，M12 设计口径）。
# =====================================================================
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DEPLOY_DIR="$(cd "${HERE}/.." && pwd)"
PROJECT="${COMPOSE_PROJECT:-blog-verify}"
WAIT_SECS="${WAIT_SECS:-180}"
BASE="${BASE:-http://localhost}"

cd "${DEPLOY_DIR}"
command -v docker >/dev/null 2>&1 || { echo "docker 不在 PATH"; exit 1; }
[ -f .env ] || { echo "缺少 deploy/.env —— 先 cp .env.example .env 并填密钥（口令红线：.env 不入库）"; exit 1; }

# compose 两文件 = 基础编排 + prod 护栏（内存上限/restart 策略），与 README §2 交付形态一致
COMPOSE=(docker compose -p "${PROJECT}" -f docker-compose.yml -f docker-compose.prod.yml)

echo "== [1/4] 构建并启动四服务（project=${PROJECT}）=="
"${COMPOSE[@]}" up -d --build || { echo "FAIL: compose up 失败"; exit 1; }

echo "== [2/4] 等待全部 healthy（上限 ${WAIT_SECS}s）=="
deadline=$((SECONDS + WAIT_SECS))
while :; do
  # 空输出按"未就绪"处理（smoke-deploy.sh M16 假绿同族防线：grep -cv 对空输入
  # 返回 0，会被误读成"全部 healthy"）；四服务都有 healthcheck 后口径才闭合
  PS_OUT=$("${COMPOSE[@]}" ps --format '{{.Name}} {{.Status}}' 2>/dev/null || true)
  if [ -n "${PS_OUT}" ] && ! echo "${PS_OUT}" | grep -qv 'healthy'; then break; fi
  if [ ${SECONDS} -ge ${deadline} ]; then
    echo "FAIL: ${WAIT_SECS}s 内未全部 healthy。卡住的容器与日志尾部："
    "${COMPOSE[@]}" ps
    # compose 的 logs 子命令要"服务名"（compose ps 第一列是容器名带 project 前缀，
    # 第二列才是服务名）——传容器名会 "no such service" 假报错，掩盖真实日志。
    while read -r svc cname; do
      echo "---- logs ${svc} (${cname}) tail 20 ----"
      "${COMPOSE[@]}" logs --tail 20 "${svc}" 2>&1 || true
    done < <("${COMPOSE[@]}" ps --format '{{.Service}} {{.Name}} {{.Status}}' \
             | grep -v 'healthy' | awk '{print $1, $2}')
    exit 1
  fi
  sleep 5
done
"${COMPOSE[@]}" ps

echo "== [3/4] 部署冒烟（6 项在线断言）=="
# COMPOSE_PROJECT 透传：smoke-deploy.sh 第 1 项要查同名 project 的 ps——
# 不传则它查默认 project（空列表）必 FAIL。M16 实跑抓出的接线错配。
BASE="${BASE:-http://localhost}" COMPOSE_PROJECT="${PROJECT}" bash "${HERE}/smoke-deploy.sh" || { echo "FAIL: smoke-deploy"; exit 1; }

echo "== [4/4] 可移植性验收（10 项：灌库/种子/开箱即用/端到端/红线）=="
PC_ARGS=()
[ "${SKIP_AI:-0}" = "1" ] && PC_ARGS+=(--skip-ai)
# ${HERE} 是 MSYS 风格（/d/...），原生 python.exe 会解析成 D:\d\...——
# 必须 cygpath -w 转 Windows 路径（与 run-load-test.sh 喂 ai_switch.py 同法，
# M16 实跑抓出：verify 第 4 步 'can't open file D:\d\毕业设计\...'）。
# 空数组展开用 ${arr[@]+"${arr[@]}"} 惯用法：`"${arr[@]:-}"` 在数组为空时会
# 扩出一个【空串参数】（M16 实跑抓出：argparse 报 unrecognized arguments）——
# set -u 下裸 "${arr[@]}" 虽可用，但惯用法在 bash 3/4 与两种 set 状态下语义一致。
PY_SCRIPT="$(cygpath -w "${HERE}/portability-check.py")"
python "${PY_SCRIPT}" --base "${BASE}" ${PC_ARGS[@]+"${PC_ARGS[@]}"} \
  || { echo "FAIL: portability-check"; exit 1; }

echo "== 收尾 =="
if [ "${KEEP_UP:-0}" = "1" ]; then
  echo "KEEP_UP=1：栈保持运行（project=${PROJECT}），验完记得 down -v"
else
  "${COMPOSE[@]}" down -v && echo "已 down -v（卷删净）"
fi
echo "VERIFY_DEPLOY_ALL_PASS"
