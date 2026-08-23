#!/bin/sh
# 幂等拉起共享浏览器栈(chromium CDP 9222 -> node 转发 9223 -> playwright-mcp 8931)。
# 已就绪立即返回 0;未就绪则启动并等待,失败返回 1。
# 调用时机(懒启动):AI 首次调用浏览器工具(browser.py)/ 网关面板 warmup(/sandbox/ensure)。
set -u

ready() {
  curl -s -o /dev/null --max-time 1 "http://127.0.0.1:$1/json/version" 2>/dev/null
}

# 快路径:CDP 已就绪 => 浏览器栈已在运行(chromium 与转发同生命周期)
if ready 9222; then
  exit 0
fi

export PLAYWRIGHT_BROWSERS_PATH=/usr/local/share/ms-playwright

CDP_PORT=9222
CDP_PROFILE=/tmp/aiwb-cdp-profile
rm -rf "$CDP_PROFILE"
mkdir -p "$CDP_PROFILE"

# 清理可能残留的旧进程(容器 stop/start 后进程不会自动恢复)
pkill -f 'remote-debugging-port=9222' 2>/dev/null
pkill -f cdp-forward.js 2>/dev/null
sleep 0.3

CHROME_BIN=$(find "$PLAYWRIGHT_BROWSERS_PATH" -maxdepth 3 -type f -path '*/chrome-linux*/chrome' 2>/dev/null | head -n1)
if [ -z "$CHROME_BIN" ] || [ ! -x "$CHROME_BIN" ]; then
  echo "[ensure-browser] fatal: chromium binary not found" >&2
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

i=0
while [ "$i" -lt 60 ]; do
  if ready "$CDP_PORT"; then break; fi
  i=$((i + 1))
  sleep 0.5
done
if ! ready "$CDP_PORT"; then
  echo "[ensure-browser] fatal: chromium CDP not ready in 30s" >&2
  exit 1
fi

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

i=0
while [ "$i" -lt 20 ]; do
  if curl -s -o /dev/null --max-time 1 "http://127.0.0.1:9223/json/version" 2>/dev/null; then break; fi
  i=$((i + 1))
  sleep 0.5
done

# playwright-mcp(网关面板截图/接管走它;AI 工具走 CDP 直连)
env -u LD_PRELOAD playwright-mcp --caps vision \
  --port 8931 --host 0.0.0.0 --allowed-hosts '*' --shared-browser-context \
  --cdp-endpoint "http://127.0.0.1:$CDP_PORT" \
  >/tmp/playwright-mcp.log 2>&1 &

i=0
while [ "$i" -lt 20 ]; do
  if curl -s -o /dev/null --max-time 1 -X POST \
      -H 'Content-Type: application/json' -d '{}' "http://127.0.0.1:8931/mcp" 2>/dev/null; then
    echo "[ensure-browser] ok"
    exit 0
  fi
  i=$((i + 1))
  sleep 0.5
done

echo "[ensure-browser] warn: browser stack up, mcp not ready in 10s" >&2
exit 0
