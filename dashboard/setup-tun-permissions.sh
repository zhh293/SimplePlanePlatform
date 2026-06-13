#!/bin/bash
# SimplePlane TUN Adapter — 一次性权限设置
# 运行一次后，dashboard 就能直接启动/停止 tun-adapter，无需每次输入密码
#
# 用法: 在终端中执行
#   cd dashboard && chmod +x setup-tun-permissions.sh && ./setup-tun-permissions.sh

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"
TUN_BIN="$PROJECT_ROOT/tun-adapter/target/release/tun-adapter"
RESTORE_DNS="$PROJECT_ROOT/restore-dns.sh"

echo "╔══════════════════════════════════════════════════╗"
echo "║   SimplePlane TUN Adapter — 权限设置            ║"
echo "╚══════════════════════════════════════════════════╝"
echo ""

if [ ! -f "$TUN_BIN" ]; then
  echo "❌ tun-adapter 二进制文件不存在: $TUN_BIN"
  echo "   请先编译: cd $PROJECT_ROOT/tun-adapter && cargo build --release"
  exit 1
fi

USER_NAME=$(whoami)
SUDOERS_FILE="/etc/sudoers.d/simpleplane-tun"

echo "将为用户 '$USER_NAME' 配置 sudo 免密启动/停止 tun-adapter。"
echo "二进制路径: $TUN_BIN"
echo ""
echo "这需要输入你的 Mac 管理员密码（仅此一次）："
echo ""

# Create sudoers rule: allow running tun-adapter, killing it, toggling the
# system proxy, and running the DNS recovery script without password
SUDOERS_CONTENT="# SimplePlane TUN Adapter - allow dashboard to start/stop without password
$USER_NAME ALL=(root) NOPASSWD: $TUN_BIN *
$USER_NAME ALL=(root) NOPASSWD: /bin/kill *
$USER_NAME ALL=(root) NOPASSWD: /usr/bin/pkill -f tun-adapter
$USER_NAME ALL=(root) NOPASSWD: /usr/sbin/networksetup
$USER_NAME ALL=(root) NOPASSWD: /bin/bash $RESTORE_DNS"

# Write to temp file first, then validate with visudo -c
TEMP_FILE=$(mktemp)
echo "$SUDOERS_CONTENT" > "$TEMP_FILE"

# Validate syntax
if sudo visudo -cf "$TEMP_FILE" 2>/dev/null; then
  sudo cp "$TEMP_FILE" "$SUDOERS_FILE"
  sudo chmod 0440 "$SUDOERS_FILE"
  sudo chown root:wheel "$SUDOERS_FILE"
  rm -f "$TEMP_FILE"
  echo ""
  echo "✅ 设置成功！sudoers 规则已写入: $SUDOERS_FILE"
  echo "   Dashboard 现在可以直接启动/停止 TUN 模式了。"
else
  rm -f "$TEMP_FILE"
  echo ""
  echo "❌ sudoers 语法验证失败，未做任何修改。"
  exit 1
fi

echo ""
echo "📋 已配置的规则:"
echo "   - sudo tun-adapter（免密启动）"
echo "   - sudo kill（免密停止进程）"
echo "   - sudo pkill -f tun-adapter（免密清理）"
echo "   - sudo networksetup（免密开关系统代理）"
echo "   - sudo restore-dns.sh（免密 DNS 兜底还原）"
echo ""
echo "⚠️  如果 tun-adapter 二进制路径发生变化（如重新编译到其他目录），需要重新运行此脚本。"
echo "   要移除此配置，运行: sudo rm $SUDOERS_FILE"
