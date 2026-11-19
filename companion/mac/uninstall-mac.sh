#!/usr/bin/env bash
set -euo pipefail
rm -rf "$HOME/Library/Services/发送到大爆手机.workflow" "$HOME/Library/Services/发送到手机便签 (大爆).workflow" "$HOME/.local/bin/bgoom-send"
/System/Library/CoreServices/pbs -flush >/dev/null 2>&1 || true
echo "✅ 已卸载大爆 macOS 右键快捷服务"
