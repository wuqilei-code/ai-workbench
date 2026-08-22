#!/usr/bin/env sh
set -eu

WORKSPACE_ROOT="${WORKSPACE_ROOT:-/workspace}"
PER_USER_DATA_ROOT="${WORKSPACE_PER_USER_DATA_ROOT:-/data/users}"

# 1. 工作区与数据目录
mkdir -p "$WORKSPACE_ROOT/users" "$PER_USER_DATA_ROOT" /data/audit

# 2. per-user uid 隔离地基
#    users/ (root 0755):每用户子目录 /data/users/<uid>/home 由 UserProvisioningService 创建(0700)
#    /data /workspace /workspace/users (0755):中间目录须 traversable(x 位),
#    否则 per-user uid 进程无法经路径前缀访问自己的 home。
#    敏感数据的真正隔离在叶子目录(0700),不在中间目录。
chmod 0755 "$PER_USER_DATA_ROOT"
chmod 0755 "$WORKSPACE_ROOT" "$WORKSPACE_ROOT/users"

cat <<'EOF'
===================================================================
 AI Workbench starting... (deepagents)
   workspace:   $WORKSPACE_ROOT
   gateway:     http://127.0.0.1:8080  (internal)
   public:      http://0.0.0.0:80
   health:      curl http://localhost/api/health
   users data:  /data/users
===================================================================
EOF

exec "$@"