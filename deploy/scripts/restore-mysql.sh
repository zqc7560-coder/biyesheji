#!/usr/bin/env bash
# =====================================================================
# 数据库恢复（M12 / §1.4）：zcat 备份包 → mysql 管道导入
# 用法：blog-restore.sh /path/blog_2026-09-13_0300.sql.gz
#
# 为什么是"管道导入"而不是"塞进容器再 source"：少一次文件落盘（备份包含用户
# 散列等敏感数据，能不过磁盘就不过）。exec -T 同备份脚本的理由（cron/无 TTY）。
# =====================================================================
set -euo pipefail

if [ $# -ne 1 ] || [ ! -f "$1" ]; then
  echo "用法: $0 <备份文件.sql.gz>" >&2
  exit 1
fi

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
COMPOSE_DIR="${BLOG_DEPLOY_DIR:-$(dirname "$SCRIPT_DIR")}"

echo "[blog-restore] 将用 $1 覆盖 blog_db —— 该库现有数据会被同名表重建替换。"
if [ -t 0 ]; then
  read -r -p "确认继续？(yes/N) " ans
  [ "$ans" = "yes" ] || { echo "已取消"; exit 1; }
fi

gzip -t "$1"   # 先验包体，坏包直接退出，不执行半截恢复
# 注意 blog_db 参数不可省：mysqldump 单库模式（不带 --databases）不生成 USE 语句，
# 导入端必须显式选库，否则第一张表就 ERROR 1046 No database selected——
# 这个 bug 是 M12 本机影子库实跑（mysqldump→gzip→mysql 全管道）抓出来的。
zcat "$1" | ( cd "${COMPOSE_DIR}" && docker compose exec -T mysql \
  sh -c 'exec mysql -uroot -p"$MYSQL_ROOT_PASSWORD" --default-character-set=utf8mb4 blog_db' )

echo "[blog-restore] 完成。建议抽查：docker compose exec mysql sh -c \
'mysql -uroot -p\"$MYSQL_ROOT_PASSWORD\" -e \"select count(*) from blog_db.sys_user;\"'"
