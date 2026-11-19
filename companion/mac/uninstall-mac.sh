#!/usr/bin/env bash
set -euo pipefail
rm -rf "$HOME/Library/Services/发送到大爆手机.workflow" \
       "$HOME/Library/Services/发送到手机便签 (大爆).workflow" \
       "$HOME/Library/Application Support/BGOOM/BgoomNotify.app" \
       "$HOME/.local/bin/bgoom-send" \
       "$HOME/.local/bin/bgoom-notify"
/System/Library/CoreServices/pbs -flush >/dev/null 2>&1 || true
echo "✅ 已卸载大爆 macOS 右键快捷服务"
