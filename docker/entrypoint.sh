#!/usr/bin/env sh
set -eu

OPENWORK_WORKSPACE="${OPENWORK_WORKSPACE:-/workspace}"
OPENCODE_DATA_DIR="${OPENCODE_DATA_DIR:-/data/opencode}"

# 1. 工作区与数据目录
#    Phase 2 起 opencode 由网关 per-user ProcessManager 管理,不再需要 root 的
#    /root/.config/opencode 全局配置目录;仅保留 /data/opencode 作为模板首次播种的回退源。
mkdir -p "$OPENWORK_WORKSPACE/users" "$OPENCODE_DATA_DIR" /data/audit

# 2. opencode 配置回退源(供第 3 段模板首启播种;管理员后续通过 apply 接口覆写)
if [ ! -f "$OPENCODE_DATA_DIR/opencode.json" ]; then
  cp /opt/aiworkbench/opencode.json.tpl "$OPENCODE_DATA_DIR/opencode.json"
fi
# opencode.env 存放 API key(由 admin apply 接口生成),首次启动置空
touch "$OPENCODE_DATA_DIR/opencode.env"

# 3. per-user uid 隔离地基 —— 配置模板真相源 + 每用户数据根
#    template/   (root 0700):applyConfig 渲染真相源、PerUserConfigWriter 拷贝源,非 root 不可读
#    /data/opencode (root 0750):仅 root 需访问(per-user 进程走 /data/users/<uid>/,不经此目录)
#    users/      (root 0755):每用户子目录 /data/users/<uid>/home 由 UserProvisioningService 创建(0700)
#    /data /workspace /workspace/users (0755):中间目录须 traversable(x 位),
#    否则 per-user uid 进程无法经路径前缀访问自己的 /data/users/<uid>/home。
#    敏感数据的真正隔离在叶子目录(0700),不在中间目录。
TEMPLATE_DIR="${OPENCODE_TEMPLATE_DIR:-/data/opencode/template}"
PER_USER_DATA_ROOT="${OPENCODE_PER_USER_DATA_ROOT:-/data/users}"
mkdir -p "$TEMPLATE_DIR/agents" "$TEMPLATE_DIR/skills" "$PER_USER_DATA_ROOT"
chmod 0750 "$OPENCODE_DATA_DIR"          # 仅 root 可进入(模板真相源所在)
chmod 0700 "$TEMPLATE_DIR"               # 模板目录:非 root 完全不可读
chmod 0755 "$PER_USER_DATA_ROOT"         # 中间目录:per-user uid 须 traverse
chmod 0755 "$OPENWORK_WORKSPACE" "$OPENWORK_WORKSPACE/users"  # workspace 中间目录同理
# 首启播种模板:优先用当前 live 配置(捕获已 apply 的 provider/key),回退 .tpl
if [ ! -f "$TEMPLATE_DIR/opencode.json" ]; then
  if [ -f "$OPENCODE_DATA_DIR/opencode.json" ]; then
    cp "$OPENCODE_DATA_DIR/opencode.json" "$TEMPLATE_DIR/opencode.json"
  else
    cp /opt/aiworkbench/opencode.json.tpl "$TEMPLATE_DIR/opencode.json"
  fi
fi
if [ ! -f "$TEMPLATE_DIR/opencode.env" ] && [ -f "$OPENCODE_DATA_DIR/opencode.env" ]; then
  cp "$OPENCODE_DATA_DIR/opencode.env" "$TEMPLATE_DIR/opencode.env"
  chmod 0600 "$TEMPLATE_DIR/opencode.env"
fi
if [ ! -f "$TEMPLATE_DIR/agents/chat.md" ]; then
  cp /opt/aiworkbench/agents/chat.md "$TEMPLATE_DIR/agents/chat.md"
fi
# agents 与 skills 是静态内置资源,以镜像版为准:每次启动覆盖模板,
# 确保提示/技能升级后能传播(否则首次 seed 后永不更新)。
# opencode.json/opencode.env 是 admin apply 的 live 配置,上面保留 if [ ! -f ] 不覆盖。
mkdir -p "$TEMPLATE_DIR/agents"
cp /opt/aiworkbench/agents/*.md "$TEMPLATE_DIR/agents/"
if [ -d /opt/aiworkbench/skills ]; then
  mkdir -p "$TEMPLATE_DIR/skills"
  cp -r /opt/aiworkbench/skills/. "$TEMPLATE_DIR/skills/"
fi

cat <<'EOF'
===================================================================
 AI Workbench starting...
   workspace:   $OPENWORK_WORKSPACE
   opencode:    per-user on-demand (127.0.0.1:141XX, uid-isolated)
   gateway:     http://127.0.0.1:8080  (internal)
   public:      http://0.0.0.0:80
   health:      curl http://localhost/api/health
   template:    /data/opencode/template  (per-user isolation source)
   users data:  /data/users
===================================================================
EOF

exec "$@"
