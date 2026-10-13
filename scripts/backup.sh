#!/usr/bin/env bash
# AI Workbench 备份脚本(MySQL 应用库 + Postgres checkpoint + 用户工作区卷)。
#
# 用法:
#   bash scripts/backup.sh [输出目录]        # 默认 ./backups
#
# 说明:
#   - MySQL:默认是 compose 自带的容器 ai-workbench-mysql-1(项目名固定 ai-workbench);
#     若为宿主机 MySQL,把 MYSQL_HOST 指向 host.docker.internal(一次性容器需能访问到它)。
#   - Postgres 为 compose 服务 ai-workbench-postgres-1(checkpoint/store)。
#   - 工作区卷名默认为 ai-workbench_workspace(<项目名>_workspace)。
#   - 过期清理:默认保留最近 KEEP 份(见下方 KEEP)。
#
# 恢复(示例):
#   gunzip -c mysql-<ts>.sql.gz    | docker run --rm -i mysql:8.4 mysql -h host.docker.internal -uaiworkbench -p"$PW" ai_workbench
#   gunzip -c postgres-<ts>.sql.gz | docker exec -i ai-workbench-postgres-1 psql -U aiworkbench -d agent_workbench
#   docker run --rm -v ai-workbench_workspace:/ws -v "$PWD/backups":/in alpine sh -c "tar xzf /in/workspace-<ts>.tar.gz -C /ws"
set -euo pipefail

OUT="${1:-./backups}"
TS="$(date +%Y%m%d-%H%M%S)"
KEEP="${KEEP:-7}"

MYSQL_HOST="${MYSQL_HOST:-host.docker.internal}"
MYSQL_USER="${SPRING_DATASOURCE_USERNAME:-aiworkbench}"
MYSQL_PASS="${SPRING_DATASOURCE_PASSWORD:-aiworkbench-pass}"
MYSQL_DB="${MYSQL_DB:-ai_workbench}"
PG_CONTAINER="${PG_CONTAINER:-ai-workbench-postgres-1}"
PG_USER="${POSTGRES_USER:-aiworkbench}"
PG_DB="${PG_DB:-agent_workbench}"
WS_VOLUME="${WS_VOLUME:-ai-workbench_workspace}"

mkdir -p "$OUT"
echo "[backup] -> $OUT/$TS"

# 1) MySQL(应用:用户/会话/消息/模板/技能/任务…)
docker run --rm "docker.m.daocloud.io/mysql:8.4" \
  mysqldump -h "$MYSQL_HOST" -u"$MYSQL_USER" -p"$MYSQL_PASS" \
  --single-transaction --quick --default-character-set=utf8mb4 "$MYSQL_DB" \
  | gzip > "$OUT/mysql-$TS.sql.gz"
echo "[backup] mysql ok"

# 2) Postgres(deepagents checkpoint/store)
docker exec "$PG_CONTAINER" pg_dump -U "$PG_USER" "$PG_DB" | gzip > "$OUT/postgres-$TS.sql.gz"
echo "[backup] postgres ok"

# 3) 用户工作区卷
docker run --rm -v "${WS_VOLUME}:/ws" -v "$(cd "$OUT" && pwd)":/out alpine \
  tar czf "/out/workspace-$TS.tar.gz" -C /ws .
echo "[backup] workspace ok"

# 4) 保留最近 KEEP 份(按前缀分组清理)
for prefix in mysql postgres workspace; do
  ls -1t "$OUT"/${prefix}-*.gz 2>/dev/null | tail -n +$((KEEP + 1)) | xargs -r rm -f
done
echo "[backup] done; kept last $KEEP sets in $OUT"
