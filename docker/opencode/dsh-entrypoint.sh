#!/bin/sh
# 每用户 dsh agent 容器入口 wrapper:
#   1) 后台启动【无头 Chromium】(CDP 9222) + CDP 转发(9223);
#   2) 后台常驻 playwright-mcp(8931,连同一 Chromium)——与 agent-entrypoint.sh 完全一致;
#   3) exec dsh-bridge(node):起 dsh 运行时(stdio JSON-RPC)并以 opencode 兼容 API 监听 4096。
#
# 说明:dsh 运行时是官方 PyPI wheel 内的单文件 Node 可执行程序
#   (/opt/aiworkbench/dsh/bin/dsh-jsonrpc-agent),无需 npm 依赖解析。
set -u

export PLAYWRIGHT_BROWSERS_PATH=/usr/local/share/ms-playwright
export PLAYWRIGHT_MCP_ISOLATED=false

# ---------- 1. 启动共享 Chromium(无头,CDP 9222) ----------
CDP_PORT=9222
CDP_PROFILE=/tmp/aiwb-cdp-profile
rm -rf "$CDP_PROFILE"
mkdir -p "$CDP_PROFILE"

CHROME_BIN=$(find "$PLAYWRIGHT_BROWSERS_PATH" -maxdepth 3 -type f -path '*/chrome-linux*/chrome' 2>/dev/null | head -n1)
if [ -z "$CHROME_BIN" ] || [ ! -x "$CHROME_BIN" ]; then
  echo "[dsh-entrypoint] fatal: chromium binary not found under $PLAYWRIGHT_BROWSERS_PATH" >&2
  exit 1
fi

env -u LD_PRELOAD "$CHROME_BIN" \
  --headless=new --no-sandbox --disable-dev-shm-usage --disable-gpu \
  --remote-debugging-port="$CDP_PORT" \
  --remote-allow-origins=* \
  --user-data-dir="$CDP_PROFILE" \
  --window-size=1920,1080 --force-device-scale-factor=1 \
  --no-first-run --no-default-browser-check --disable-features=Translate,OptimizationHints \
  about:blank >/tmp/chromium.log 2>&1 &
CHROME_PID=$!

i=0
while [ "$i" -lt 40 ]; do
  if curl -s -o /dev/null --max-time 1 "http://127.0.0.1:$CDP_PORT/json/version" 2>/dev/null; then
    break
  fi
  i=$((i + 1))
  sleep 0.5
done
if [ "$i" -ge 40 ]; then
  echo "[dsh-entrypoint] fatal: chromium (pid=$CHROME_PID) CDP not ready in 20s; see /tmp/chromium.log" >&2
  kill "$CHROME_PID" 2>/dev/null
  exit 1
fi

# ---------- 2. CDP TCP 转发(0.0.0.0:9223 → 127.0.0.1:9222) ----------
cat > /tmp/cdp-forward.js <<'EOF'
const net = require('net');
const LISTEN = 9223, TARGET = 9222;
const server = net.createServer((socket) => {
  const target = net.connect(TARGET, '127.0.0.1', () => {
    socket.pipe(target);
    target.pipe(socket);
  });
  socket.on('error', () => target.destroy());
  target.on('error', () => socket.destroy());
});
server.listen(LISTEN, '0.0.0.0', () => console.error('cdp-forward listening on 0.0.0.0:' + LISTEN));
EOF
env -u LD_PRELOAD node /tmp/cdp-forward.js >/tmp/cdp-forward.log 2>&1 &
FORWARD_PID=$!

i=0
while [ "$i" -lt 10 ]; do
  if curl -s -o /dev/null --max-time 1 "http://127.0.0.1:9223/json/version" 2>/dev/null; then
    break
  fi
  i=$((i + 1))
  sleep 0.5
done
if [ "$i" -ge 10 ]; then
  echo "[dsh-entrypoint] fatal: cdp-forward (pid=$FORWARD_PID) not ready; see /tmp/cdp-forward.log" >&2
  kill "$CHROME_PID" 2>/dev/null
  exit 1
fi

# ---------- 3. playwright-mcp(连接到上面同一个 Chromium) ----------
env -u LD_PRELOAD playwright-mcp --caps vision \
  --port 8931 --host 0.0.0.0 --allowed-hosts '*' --shared-browser-context \
  --cdp-endpoint "http://127.0.0.1:$CDP_PORT" \
  >/tmp/playwright-mcp.log 2>&1 &
MCP_PID=$!

i=0
while [ "$i" -lt 20 ]; do
  if curl -s -o /dev/null --max-time 1 -X POST \
      -H 'Content-Type: application/json' -d '{}' "http://127.0.0.1:8931/mcp" 2>/dev/null; then
    break
  fi
  i=$((i + 1))
  sleep 0.5
done
if [ "$i" -ge 20 ]; then
  echo "[dsh-entrypoint] warn: playwright-mcp daemon (pid=$MCP_PID) not ready in 10s; see /tmp/playwright-mcp.log" >&2
fi

trap 'kill "$MCP_PID" 2>/dev/null; kill "$CHROME_PID" 2>/dev/null; kill "$FORWARD_PID" 2>/dev/null' EXIT INT TERM

# ---------- 4. dsh-bridge(主进程):dsh 运行时 + opencode 兼容 API :4096 ----------
export PORT=4096
export DSH_CORDIS_CONFIG=/opt/aiworkbench/dsh/cordis.yml
export DSH_RUNTIME_BIN=/opt/aiworkbench/dsh/bin/dsh-jsonrpc-agent
exec node /opt/aiworkbench/dsh-bridge/bridge.js "$@"