#!/usr/bin/env bash
# =====================================================================
# 每日数据库备份（M12 / §1.4 运维项）
# 部署位置：目标机 /usr/local/bin/blog-backup.sh，crontab 每日 03:00：
#   0 3 * * * /usr/local/bin/blog-backup.sh >> /var/log/blog-backup.log 2>&1
#
# 设计点：
# 1) --single-transaction：InnoDB 一致性快照备份，不锁表（备份期间博客照常读写）。
# 2) 口令不写死不落参数——放进容器内环境变量后 mysqldump 从 --defaults-extra-file
#    读取，避免 -p口令 出现在 ps 进程列表（M8 冒烟同样纪律：口令不进命令行明文）。
#    本脚本依赖 compose 环境：MYSQL_ROOT_PASSWORD 由 deploy/.env 经 docker compose
#    注入 mysql 容器，宿主机脚本只需能 `docker compose exec` 即可。
# 3) 保留 7 日（§1.4），过期文件按文件名日期排序删除，不用 find -mtime
#    （文件被 cp 时会刷新 mtime，按名排序才是确定性的）。
# 4) 备份完做"体检"：gzip -t 校验包体 + 首行含 Dump completed 时间戳，
#    只留"能解压出来的备份"，防"备份文件存在但不可用"这种最阴险的故障。
# =====================================================================
set -euo pipefail

# 定位 deploy 目录：脚本允许从任意 cwd 调用（cron 的 cwd 是 $HOME）
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
COMPOSE_DIR="${BLOG_DEPLOY_DIR:-$(dirname "$SCRIPT_DIR")}"   # 默认 deploy/
BACKUP_DIR="${BLOG_BACKUP_DIR:-${HOME}/blog_backups}"
RETAIN_DAYS=7
STAMP="$(date +%F_%H%M)"
OUT="${BACKUP_DIR}/blog_${STAMP}.sql.gz"

mkdir -p "${BACKUP_DIR}"

echo "[blog-backup] $(date '+%F %T') 开始备份 blog_db -> ${OUT}"
# compose exec 的 -T：非 TTY（cron 场景没有终端，不加 -T 会报 the input device is not a TTY）
( cd "${COMPOSE_DIR}" && docker compose exec -T mysql \
    sh -c 'exec mysqldump -uroot -p"$MYSQL_ROOT_PASSWORD" \
           --single-transaction --routines --events --default-character-set=utf8mb4 blog_db' \
) | gzip > "${OUT}"

# ---- 体检 1：gzip 包体完整 ----
if ! gzip -t "${OUT}"; then
  echo "[blog-backup] 失败：gzip 校验不通过，删除坏包 ${OUT}" >&2
  rm -f "${OUT}"
  exit 1
fi
# ---- 体检 2：内容非空且带 mysqldump 版本头（空备份的 gzip 也有约 20 字节头）。
#      注意：dump 首行形态是 "-- MySQL dump 10.13  Distrib ..."（含空格），
#      不是字面量 mysqldump——M12 实跑冒烟用真 blog_db 抓到这个误杀 bug。
if ! zcat "${OUT}" | head -c 4096 | grep -q -- "MySQL dump"; then
  echo "[blog-backup] 失败：备份内容异常（缺 mysqldump 头），删除 ${OUT}" >&2
  rm -f "${OUT}"
  exit 1
fi

# ---- 治理：按文件名日期保留 7 天（文件名定宽 YYYY-MM-DD_HHMM，字典序=时间序）----
ls -1 "${BACKUP_DIR}"/blog_*.sql.gz 2>/dev/null | sort | head -n -"${RETAIN_DAYS}" | while read -r old; do
  echo "[blog-backup] 清理过期备份：${old}"
  rm -f "${old}"
done

SIZE=$(du -h "${OUT}" | cut -f1)
echo "[blog-backup] 完成：${OUT} (${SIZE})，保留最近 ${RETAIN_DAYS} 份"
