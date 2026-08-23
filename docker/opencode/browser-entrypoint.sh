#!/bin/sh
# 沙箱容器入口(懒启动改造):只做保活,不再随容器启动浏览器。
# 共享浏览器栈(chromium + cdp-forward 9223 + playwright-mcp 8931)由
# ensure-browser.sh 按需拉起 —— AI 首次调用浏览器工具 / 网关面板 warmup 时。
# 好处:纯代码/文档类会话不再常驻一个 ~300MB 的 Chromium。
exec tail -f /dev/null
