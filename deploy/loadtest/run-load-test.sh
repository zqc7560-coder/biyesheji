#!/usr/bin/env bash
# =====================================================================
# M16 正式压测执行器（阶段七交付物）
#
# 职责：把"跑一轮 JMeter"从 10 个手工参数收敛成一条命令——
#   1) 解析 --scenario（list|detail|ai|degraded|full），拼出对应的 -J 属性集
#   2) degraded 场景自动做故障注入：PUT ai_enabled=false → 压 → 恢复 true
#      （R-03 应急口的自动化，注入/恢复成对出现在同一个 trap 里，中断也不留脏配置）
#   3) 管理员口令从 deploy/.env 读（仓库零明文；也支持 --password-env 传变量名）
#   4) 产物落 output/<场景>-<时间戳>/：jtl + HTML 报告 + 运行参数快照
#
# 用法示例（JMETER_HOME 必填，指向 E 盘解压目录——禁 C 盘红线）：
#   JMETER_HOME=E:/develop/apache-jmeter-5.6.3 ./run-load-test.sh --scenario list
#   JMETER_HOME=... ./run-load-test.sh --scenario ai      # PRIME 暖缓存+20并发命中
#   JMETER_HOME=... ./run-load-test.sh --scenario degraded # 熔断注入+500并发闸门
#   JMETER_HOME=... ./run-load-test.sh --scenario full    # ①②③ 顺序全跑
#   BASE=http://localhost ./run-load-test.sh --scenario list  # 经 Nginx 打全链路
#
# 前置：目标环境已灌压测数据（python seed-loadtest-data.py），AI 场景需
#       内置模型可用（PRIME 真实调一次模型，网络抖动按验证报告 §3.2 复跑即可）。
# =====================================================================
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DEPLOY_DIR="$(cd "${HERE}/.." && pwd)"
JMX="${HERE}/blog-load-test.jmx"
# 产物目录默认 E 盘纯 ASCII 路径：JMeter 报告生成器写不进含中文的路径
# （"folder does not exist and parent folder is not writable" 实锤——与 JaCoCo
# 中文路径静默坑同族，M11 台账教训在压测工具上复现）。HTML 报告体积大，
# 本就不该进 git；需要归档的只有 jtl 摘要与关键指标，由人工摘录进报告文档。
OUT_ROOT="${LOADTEST_OUT:-E:/develop/loadtest-output}"

# ---- 场景参数默认值（对齐《部署与测试.md》§2 四场景口径） ----
BASE_URL="${BASE:-http://127.0.0.1:8080}"
HOST="$(printf '%s' "${BASE_URL}" | sed -E 's#^[a-z]+://##; s#[:/].*$##')"
PORT="$(printf '%s' "${BASE_URL}" | sed -nE 's#^[a-z]+://[^:/]+:([0-9]+).*#\1#p')"
PORT="${PORT:-80}"
PROTO="$(printf '%s' "${BASE_URL}" | sed -En 's#^([a-z]+)://.*#\1#p')"
DURATION="${DURATION:-300}"          # 定时长场景的秒数
SHORT_RUN="${SHORT_RUN:-0}"          # 冒烟模式：1=30s 短跑，先验脚本本身
SCENARIO="list"

while [ $# -gt 0 ]; do
  case "$1" in
    --scenario) SCENARIO="$2"; shift 2;;
    --duration) DURATION="$2"; shift 2;;
    *) echo "未知参数: $1"; exit 2;;
  esac
done

if [ "${SHORT_RUN}" = "1" ]; then DURATION=30; fi

[ -n "${JMETER_HOME:-}" ] || { echo "必须先 export JMETER_HOME（如 E:/develop/apache-jmeter-5.6.3）"; exit 2; }
JMETER_BIN="${JMETER_HOME}/bin/jmeter.bat"
[ -f "${JMETER_BIN}" ] || { echo "找不到 ${JMETER_BIN}"; exit 2; }

# ---- 管理口令：优先环境变量 BLOG_ADMIN_PASSWORD，回退读 deploy/.env ----
ADMIN_PASS="${BLOG_ADMIN_PASSWORD:-}"
if [ -z "${ADMIN_PASS}" ] && [ -f "${DEPLOY_DIR}/.env" ]; then
  ADMIN_PASS="$(sed -n 's/^BLOG_ADMIN_PASSWORD=//p' "${DEPLOY_DIR}/.env" | head -1)"
fi
[ -n "${ADMIN_PASS}" ] || { echo "管理员口令为空：设 BLOG_ADMIN_PASSWORD 或提供 ${DEPLOY_DIR}/.env"; exit 2; }

# ---- 属性拼装（ai_csv/ai_body_dir 在 staging 段以 E 盘路径追加，此处不放） ----
ATTRS=(-Jhost="${HOST}" -Jport="${PORT}" -Jproto="${PROTO}"
       -Jadmin_user="${ADMIN_USER:-admin}" -Jadmin_pass="${ADMIN_PASS}")
# 详情场景轮播的文章 id 区间：JMX 里有缺省值（历史开发库 13..42），但每次
# seed-loadtest-data.py 灌出来的 id 随目标库而定（全新 compose 库是 1..30，
# 打印 id_min/id_max）。不转发就会 404 幽灵文章、详情路径测成"404 路径性能"，
# 数字看着正常但语义全错——故 runner 提供显式收口，且缺省即告警。
if [ -n "${ID_MIN:-}" ] && [ -n "${ID_MAX:-}" ]; then
  ATTRS+=(-Jid_min="${ID_MIN}" -Jid_max="${ID_MAX}")
elif [ "${SCENARIO:-}" = "detail" ]; then
  echo "警告：未设 ID_MIN/ID_MAX，详情场景将用 JMX 缺省 id 区间——请先看 seed 脚本打印的 id_min/id_max" >&2
fi

flag_common() {
  # 只开指定组，其余显式 0（线程数 0 的调度组 JMeter 直接跳过）；
  local s="$1"
  # PRIME 只在 ai 场景跑：它是真实模型调用（~800ms/发），其他场景带上纯属烧钱。
  # （M16 首跑踩坑：不显式置 0 时 JMX 属性缺省 30，list/detail 也各打了 30 发真调用）
  if [ "${s}" = "ai" ]; then ATTRS+=(-Jprime_loops=30); else ATTRS+=(-Jprime_loops=0); fi
  if [ "${s}" = "list" ];  then ATTRS+=(-Jlist_threads=200);   else ATTRS+=(-Jlist_threads=0);   fi
  if [ "${s}" = "detail" ];then ATTRS+=(-Jdetail_threads=200); else ATTRS+=(-Jdetail_threads=0); fi
  if [ "${s}" = "ai" ];    then ATTRS+=(-Jai_threads=20);      else ATTRS+=(-Jai_threads=0);     fi
  if [ "${s}" = "degraded" ];then ATTRS+=(-Jdeg_threads=500 -Jdeg_loops=10); else ATTRS+=(-Jdeg_threads=0); fi
}

API_BASE="${PROTO}://${HOST}:${PORT}"

# ---- 降级场景的故障注入（R-03 一键熔断口的自动化） ----
# 口令经环境变量传子进程，绝不走 argv（命令行参数在任务管理器/ps 里是明文的）
ai_switch() { # $1=true|false
  local out
    # ${HERE} 是 MSYS 风格路径（/d/...），原生 python.exe 会解析成 D:\d\...——
  # 必须 cygpath -w 转成 Windows 路径再传（M8 bash 混编教训的同族坑）
  local ascript
  ascript="$(cygpath -w "${HERE}/../tools/ai_switch.py")"
  out="$(BLOG_ADMIN_PASSWORD="${ADMIN_PASS}" python "${ascript}" --base "${API_BASE}" --value "$1")"
  echo "ai_enabled -> $1 (${out})"
}
restore_ai() { ai_switch true >/dev/null 2>&1 || echo "警告：ai_enabled 恢复失败，需手工检查后台系统参数！"; }

case "${SCENARIO}" in
  list)     flag_common list;;
  detail)   flag_common detail;;
  ai)       flag_common ai;;
  degraded) flag_common degraded;;
  full)     # 顺序跑三个正向场景（degraded 含注入不能混在 full 里静默跑）
    for s in list detail ai; do
      echo "=== FULL: scenario=${s} ==="
      SHORT_RUN="${SHORT_RUN}" DURATION="${DURATION}" JMETER_HOME="${JMETER_HOME}" \
        BASE="${BASE_URL}" bash "$0" --scenario "${s}"
    done
    exit 0;;
  *) echo "场景必须是 list|detail|ai|degraded|full"; exit 2;;
esac

if [ "${SCENARIO}" = "degraded" ]; then
  ai_switch false
  trap restore_ai EXIT
fi

TS="$(date +%Y%m%d-%H%M%S)"
OUT_DIR="${OUT_ROOT}/${SCENARIO}-${TS}"
mkdir -p "${OUT_DIR}"

# ---- staging：JMX 与请求体先拷到纯 ASCII 目录再喂给 jmeter.bat ----
# 仓库路径含中文（D:\毕业设计），jmeter.bat 经 cmd 传参会发生双重破坏：
#   ① MSYS 风格 /d/xx 被 cmd 解析成 D:\d\xx；② 中文段在 GBK 代码页往返中变问号。
#   （实跑报错 'D:\d\??ҵ???\...' 即为证据——与 JaCoCo 中文路径静默坑同族。）
# ai_switch.py/ai_bodies 都在 JMX 相对引用之外（用绝对属性传入），staging 零副作用。
STAGE_DIR="${LOADTEST_STAGE:-E:/develop/loadtest-stage}"
mkdir -p "${STAGE_DIR}"
cp -f "${JMX}" "${STAGE_DIR}/blog-load-test.jmx"
rm -rf "${STAGE_DIR}/ai_bodies"
cp -rf "${HERE}/ai_bodies" "${STAGE_DIR}/ai_bodies"
cp -f "${HERE}/ai_bodies.csv" "${STAGE_DIR}/ai_bodies.csv"

echo "== JMeter 5.6.3 | scenario=${SCENARIO} | ${API_BASE} | duration=${DURATION}s | SHORT_RUN=${SHORT_RUN} =="
echo "${ATTRS[*]}" | tr ' ' '\n' | grep -v admin_pass > "${OUT_DIR}/params.txt"

"${JMETER_BIN}" -n -t "${STAGE_DIR}/blog-load-test.jmx" -l "${OUT_DIR}/result.jtl" \
  -e -o "${OUT_DIR}/report" \
  -Jlist_duration="${DURATION}" -Jdetail_duration="${DURATION}" \
  -Jai_duration="$([ "${SHORT_RUN}" = 1 ] && echo 30 || echo 120)" \
  -Jai_csv="${STAGE_DIR}/ai_bodies.csv" -Jai_body_dir="${STAGE_DIR}/ai_bodies" \
  "${ATTRS[@]}"

# ---- 关键指标速读：P95 / 错误率 / 吞吐 直接从 jtl 汇总，终端即时给结论 ----
# jtl 无 threadGroup 列（CSV 默认格式），分组靠 threadName 前缀（"TG-LIST 1-5"）；
# SETUP/PRIME 是支撑组不计入场景指标，但 PRIME 单独回显（真实生成一帧的成本参考）。
python - "${OUT_DIR}/result.jtl" <<'PY'
import csv, sys
from collections import defaultdict
lat = defaultdict(list); err = defaultdict(int); tot = defaultdict(int)
first_ts, last_end = None, 0
with open(sys.argv[1], encoding="utf-8") as f:
    for row in csv.DictReader(f):
        g = row["threadName"].split(" ")[0]
        ts, el = int(row["timeStamp"]), int(row["elapsed"])
        if first_ts is None or ts < first_ts: first_ts = ts
        if ts + el > last_end: last_end = ts + el
        tot[g] += 1
        if row["success"] != "true": err[g] += 1
        lat[g].append(el)
def pctl(xs, p):
    xs = sorted(xs); return xs[min(len(xs)-1, int(round(len(xs)*p)))]
print("---- 场景结果速览 ----")
for g in sorted(tot):
    xs = lat[g]
    dur = (last_end - first_ts) / 1000.0
    thr = len(xs) / dur if dur else 0
    tag = " *支撑组(不计场景)*" if g.startswith(("SETUP", "PRIME")) else ""
    print(f"{g:18s} n={len(xs):7d} err={err[g]:5d} ({err[g]/len(xs)*100:.2f}%) "
          f"P50={pctl(xs,.5)}ms P95={pctl(xs,.95)}ms P99={pctl(xs,.99)}ms "
          f"max={max(xs)}ms avgThr={thr:.0f}/s{tag}")
PY
echo "== 产物: ${OUT_DIR} （report/index.html 为 HTML 报告）=="
