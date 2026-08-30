#!/bin/sh
# 幂等拉起共享浏览器栈(chromium + CDP 转发 + playwright-mcp)。
# 由 agent-service 在工具调用/面板连接前 exec_run 触发;已就绪秒回,未就绪启动并等待。
# 与 browser-entrypoint.sh 复用同一套启动逻辑,但对已运行进程静默跳过。
# 退出码:0=就绪,1=失败(调用方抛错)。
set -u

export HOME="${HOME:-/tmp}"
export XDG_CACHE_HOME="${XDG_CACHE_HOME:-/tmp/.cache}"
export PLAYWRIGHT_BROWSERS_PATH="${PLAYWRIGHT_BROWSERS_PATH:-}"
export PATH="/usr/local/bin:/usr/bin:/bin"

CDP_INNER_PORT="${CDP_INNER_PORT:-9222}"
CDP_OUTER_PORT="${CDP_OUTER_PORT:-9223}"
MCP_PORT="${MCP_PORT:-8931}"

find_chromium() {
  if [ -n "${PLAYWRIGHT_BROWSERS_PATH:-}" ] && [ -d "$PLAYWRIGHT_BROWSERS_PATH" ]; then
    _found="$(find "$PLAYWRIGHT_BROWSERS_PATH" -maxdepth 4 -type f \( -name 'chrome' -o -name 'headless_shell' \) 2>/dev/null | head -n1)"
    [ -n "${_found:-}" ] && { printf '%s' "$_found"; return 0; }
  fi
  for _c in chromium chromium-browser google-chrome google-chrome-stable; do
    _p="$(command -v "$_c" 2>/dev/null)" && { printf '%s' "$_p"; return 0; }
  done
  return 1
}
CHROME_BIN="$(find_chromium || true)"

start_chromium() {
  curl -fsS "http://127.0.0.1:${CDP_INNER_PORT}/json/version" >/dev/null 2>&1 && return 0
  [ -n "$CHROME_BIN" ] || { echo "ensure-browser: 未找到 chromium" >&2; return 1; }
  mkdir -p /tmp/chrome-profile
  # shellcheck disable=SC2086
  "$CHROME_BIN" \
    --headless=new --no-sandbox --disable-dev-shm-usage --disable-gpu \
    --disable-background-networking --disable-component-update --no-first-run \
    --remote-debugging-port="${CDP_INNER_PORT}" --user-data-dir=/tmp/chrome-profile \
    about:blank >/tmp/chromium.log 2>&1 &
  _i=0
  while [ "$_i" -lt 30 ]; do
    curl -fsS "http://127.0.0.1:${CDP_INNER_PORT}/json/version" >/dev/null 2>&1 && return 0
    sleep 1; _i=$((_i+1))
  done
  echo "ensure-browser: chromium CDP 30s 内未就绪" >&2
  return 1
}

start_cdp_forward() {
  curl -fsS "http://127.0.0.1:${CDP_OUTER_PORT}/json/version" >/dev/null 2>&1 && return 0
  command -v node >/dev/null 2>&1 || { echo "ensure-browser: 缺少 node,无法启动 CDP 转发" >&2; return 1; }
  cat > /tmp/cdp-forward.js <<EOF
const net = require('net');
const inner = ${CDP_INNER_PORT}, outer = ${CDP_OUTER_PORT};
net.createServer(function (down) {
  const up = net.connect(inner, '127.0.0.1');
  down.pipe(up); up.pipe(down);
  down.on('error', function(){ up.destroy(); });
  up.on('error', function(){ down.destroy(); });
}).listen(outer, '0.0.0.0', function(){ console.log('cdp-forwarder listening :'+outer+' -> 127.0.0.1:'+inner); });
EOF
  node /tmp/cdp-forward.js >/tmp/cdp-forward.log 2>&1 &
  _i=0
  while [ "$_i" -lt 15 ]; do
    curl -fsS "http://127.0.0.1:${CDP_OUTER_PORT}/json/version" >/dev/null 2>&1 && return 0
    sleep 1; _i=$((_i+1))
  done
  echo "ensure-browser: CDP 转发未就绪" >&2
  return 1
}

start_mcp() {
  curl -fsS "http://127.0.0.1:${MCP_PORT}/" >/dev/null 2>&1 && return 0
  if ! command -v npx >/dev/null 2>&1 && ! [ -x /opt/aiworkbench/playwright-mcp/start.js ]; then
    return 0
  fi
  export PLAYWRIGHT_MCP_ISOLATED=false
  if [ -x /opt/aiworkbench/playwright-mcp/start.js ]; then
    (cd /opt/aiworkbench/playwright-mcp && exec node start.js --port "${MCP_PORT}" --cdp-endpoint "http://127.0.0.1:${CDP_INNER_PORT}" --shared-browser-context) >/tmp/mcp.log 2>&1 &
  else
    (PLAYWRIGHT_MCP_ISOLATED=false npx -y "@playwright/mcp@latest" --port "${MCP_PORT}" --cdp-endpoint "http://127.0.0.1:${CDP_INNER_PORT}" --shared-browser-context) >/tmp/mcp.log 2>&1 &
  fi
  return 0
}

start_chromium && start_cdp_forward && start_mcp
status=$?
[ "$status" -eq 0 ] && echo "ensure-browser: 浏览器栈就绪"
exit "$status"
