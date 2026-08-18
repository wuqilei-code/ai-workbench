#!/bin/sh
# 每用户 agent 容器入口(wrapper),无头浏览器:
#   1) 后台启动【无头 Chromium】,--remote-debugging-port=9222 暴露 CDP;
#   2) 后台常驻 playwright-mcp,经 --cdp-endpoint 连到同一个 Chromium(AI 与用户同浏览器同页面);
#   3) exec opencode serve ...(容器主进程)。
#
# 关键点:
#   - 无头模式 + --user-data-dir 每容器唯一(登录态/多标签共享);
#   - LD_PRELOAD(nss_wrapper)会让 Chrome 挂起,Chromium/playwright-mcp 都去掉;
#   - 新版 Chromium DevTools 强制只绑 127.0.0.1:9222,网关用 node 转发 0.0.0.0:9223 → 127.0.0.1:9222;
#   - 预览画面由网关经 CDP Page.startScreencast 按需推送(页面变化才出帧),无整窗视频流。
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
  echo "[agent-entrypoint] fatal: chromium binary not found under $PLAYWRIGHT_BROWSERS_PATH" >&2
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

# 就绪探测:CDP /json/version 可达即视为 Chromium 就绪(最多 ~20s)
i=0
while [ "$i" -lt 40 ]; do
  if curl -s -o /dev/null --max-time 1 "http://127.0.0.1:$CDP_PORT/json/version" 2>/dev/null; then
    break
  fi
  i=$((i + 1))
  sleep 0.5
done
if [ "$i" -ge 40 ]; then
  echo "[agent-entrypoint] fatal: chromium (pid=$CHROME_PID) CDP not ready in 20s; see /tmp/chromium.log" >&2
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
  echo "[agent-entrypoint] fatal: cdp-forward (pid=$FORWARD_PID) not ready; see /tmp/cdp-forward.log" >&2
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
  echo "[agent-entrypoint] warn: playwright-mcp daemon (pid=$MCP_PID) not ready in 10s; see /tmp/playwright-mcp.log" >&2
fi

trap 'kill "$MCP_PID" 2>/dev/null; kill "$CHROME_PID" 2>/dev/null; kill "$FORWARD_PID" 2>/dev/null' EXIT INT TERM

exec opencode "$@"
