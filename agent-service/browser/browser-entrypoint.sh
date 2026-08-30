#!/bin/sh
# 合并沙箱浏览器常驻入口(deepagents 单轨):
# 拉起共享 Chromium(CDP 9222) + CDP 转发(0.0.0.0:9223 -> 127.0.0.1:9222) + playwright-mcp(8931)。
# 容器以本脚本为 ENTRYPOINT,进程常驻直至容器停止;脚本本身不退出。
set -u

export HOME="${HOME:-/tmp}"
export XDG_CACHE_HOME="${XDG_CACHE_HOME:-/tmp/.cache}"
export PLAYWRIGHT_BROWSERS_PATH="${PLAYWRIGHT_BROWSERS_PATH:-}"
export PATH="/usr/local/bin:/usr/bin:/bin"

CDP_INNER_PORT="${CDP_INNER_PORT:-9222}"
CDP_OUTER_PORT="${CDP_OUTER_PORT:-9223}"
MCP_PORT="${MCP_PORT:-8931}"

# 优先用 playwright 下载的 chromium;其次系统 chromium
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

trap 'kill 0 2>/dev/null; exit 0' TERM INT

# ---- 1. 启动 Chromium(后台,CDP 绑 loopback) ----
start_chromium() {
  [ -n "$CHROME_BIN" ] || { echo "browser-entrypoint: 未找到 chromium" >&2; return 1; }
  # 幂等:已在监听则跳过
  if curl -fsS "http://127.0.0.1:${CDP_INNER_PORT}/json/version" >/dev/null 2>&1; then
    return 0
  fi
  echo "browser-entrypoint: 启动 chromium ($CHROME_BIN)"
  # shellcheck disable=SC2086
  mkdir -p /tmp/chrome-profile
  "$CHROME_BIN" \
    --headless=new \
    --no-sandbox \
    --disable-dev-shm-usage \
    --disable-gpu \
    --disable-background-networking \
    --disable-component-update \
    --no-first-run \
    --remote-debugging-port="${CDP_INNER_PORT}" \
    --user-data-dir=/tmp/chrome-profile \
    about:blank >/tmp/chromium.log 2>&1 &
  # 等待 CDP 就绪(最多 30s)
  _i=0
  while [ "$_i" -lt 30 ]; do
    curl -fsS "http://127.0.0.1:${CDP_INNER_PORT}/json/version" >/dev/null 2>&1 && return 0
    sleep 1
    _i=$((_i+1))
  done
  echo "browser-entrypoint: chromium CDP 30s 内未就绪" >&2
  return 1
}

# ---- 2. CDP 转发:0.0.0.0:9223 -> 127.0.0.1:9222(纯 node net,无外部依赖) ----
start_cdp_forward() {
  if curl -fsS "http://127.0.0.1:${CDP_OUTER_PORT}/json/version" >/dev/null 2>&1; then
    return 0
  fi
  command -v node >/dev/null 2>&1 || { echo "browser-entrypoint: 缺少 node,无法启动 CDP 转发" >&2; return 1; }
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
  echo "browser-entrypoint: 启动 CDP 转发 :${CDP_OUTER_PORT} -> :${CDP_INNER_PORT}"
  node /tmp/cdp-forward.js >/tmp/cdp-forward.log 2>&1 &
  _i=0
  while [ "$_i" -lt 15 ]; do
    curl -fsS "http://127.0.0.1:${CDP_OUTER_PORT}/json/version" >/dev/null 2>&1 && return 0
    sleep 1
    _i=$((_i+1))
  done
  echo "browser-entrypoint: CDP 转发未就绪" >&2
  return 1
}

# ---- 3. playwright-mcp(连同一 chromium,共享上下文) ----
start_mcp() {
  if curl -fsS "http://127.0.0.1:${MCP_PORT}/" >/dev/null 2>&1; then
    return 0
  fi
  if ! command -v npx >/dev/null 2>&1 && ! [ -x /opt/aiworkbench/playwright-mcp/start.js ]; then
    echo "browser-entrypoint: 缺少 playwright-mcp 启动入口(nginx 直连),跳过(CDP 仍可用)" >&2
    return 0
  fi
  echo "browser-entrypoint: 启动 playwright-mcp :${MCP_PORT}"
  export PLAYWRIGHT_MCP_ISOLATED=false
  if [ -x /opt/aiworkbench/playwright-mcp/start.js ]; then
    (cd /opt/aiworkbench/playwright-mcp && exec node start.js --port "${MCP_PORT}" --cdp-endpoint "http://127.0.0.1:${CDP_INNER_PORT}" --shared-browser-context) >/tmp/mcp.log 2>&1 &
  else
    (PLAYWRIGHT_MCP_ISOLATED=false npx -y "@playwright/mcp@latest" --port "${MCP_PORT}" --cdp-endpoint "http://127.0.0.1:${CDP_INNER_PORT}" --shared-browser-context) >/tmp/mcp.log 2>&1 &
  fi
  return 0
}

echo "browser-entrypoint: 初始化浏览器栈(inner=${CDP_INNER_PORT} outer=${CDP_OUTER_PORT} mcp=${MCP_PORT})"
start_chromium
start_cdp_forward
start_mcp

# 常驻:保持脚本不退出;容器停止时由 SIGTERM 触发 trap
echo "browser-entrypoint: 全部就绪,保持常驻"
while :; do sleep 3600; done
