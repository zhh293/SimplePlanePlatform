#!/bin/bash
# ============================================================
# TUN 启动自检脚本
# 用途：在不依赖 dashboard 的情况下，逐项验证「免密 sudo 能否
#       真正以 root 身份启动 tun-adapter 并创建 utun 设备」。
#
# ⚠️ 必须在 macOS「系统自带的 Terminal.app」里运行，
#    不要在编辑器/IDE 的内置终端里运行（内置终端常被沙箱拦截 sudo）。
#
# 用法：  cd dashboard && ./diagnose-tun.sh
# ============================================================
set -u

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
TUN_BIN="$PROJECT_ROOT/tun-adapter/target/release/tun-adapter"
TUN_CONFIG="$PROJECT_ROOT/tun-adapter/config/tun.toml"
SUDOERS_FILE="/etc/sudoers.d/simpleplane-tun"

pass() { printf "  \033[32m✅ %s\033[0m\n" "$1"; }
fail() { printf "  \033[31m❌ %s\033[0m\n" "$1"; }
info() { printf "  \033[36mℹ️  %s\033[0m\n" "$1"; }

echo "============================================================"
echo " TUN 启动自检"
echo "============================================================"

# --- 0. 当前终端环境 ---
echo
echo "[0] 当前终端环境"
info "TERM_PROGRAM = ${TERM_PROGRAM:-未知}"
if [ "${TERM_PROGRAM:-}" = "Apple_Terminal" ] || [ "${TERM_PROGRAM:-}" = "iTerm.app" ]; then
  pass "运行在系统终端中"
else
  fail "可能运行在编辑器/IDE 内置终端中 —— sudo 可能被沙箱拦截！请改用 Terminal.app"
fi

# --- 1. 二进制文件 ---
echo
echo "[1] tun-adapter 二进制"
if [ -x "$TUN_BIN" ]; then
  pass "存在且可执行: $TUN_BIN"
else
  fail "不存在或不可执行: $TUN_BIN"
  echo "      请先编译: (cd tun-adapter && cargo build --release)"
  exit 1
fi

# --- 2. 配置文件 ---
echo
echo "[2] 配置文件"
if [ -f "$TUN_CONFIG" ]; then
  pass "存在: $TUN_CONFIG"
else
  fail "不存在: $TUN_CONFIG"
  exit 1
fi

# --- 3. 免密规则文件 ---
echo
echo "[3] 免密 sudoers 规则"
if [ -f "$SUDOERS_FILE" ]; then
  pass "存在: $SUDOERS_FILE"
else
  fail "不存在 —— 请先运行: ./setup-tun-permissions.sh"
  exit 1
fi

# --- 4. 关键：sudo -n 能否免密命中规则 ---
echo
echo "[4] 验证 sudo -n 免密 (核心)"
if sudo -n true 2>/dev/null; then
  pass "sudo -n 可用（已缓存或有 NOPASSWD）"
else
  info "sudo -n true 失败属正常（没有全局 NOPASSWD），下面单独测 tun-adapter 规则"
fi

# 用 -l 列出当前规则是否包含 tun-adapter（不会真正执行）
if sudo -n -l "$TUN_BIN" >/dev/null 2>&1; then
  pass "sudo -n 已对 tun-adapter 免密授权"
else
  fail "sudo -n 未能对 tun-adapter 免密 —— 可能：1)路径不匹配 2)当前终端被沙箱拦截 sudo"
  echo "      期望规则路径: $TUN_BIN"
fi

# --- 5. 实际以 root 启动并检查设备 ---
echo
echo "[5] 实测启动（3 秒后自动停止）"
rm -f "$SCRIPT_DIR/.diag-tun.log"
sudo -n "$TUN_BIN" -c "$TUN_CONFIG" >"$SCRIPT_DIR/.diag-tun.log" 2>&1 &
DIAG_PID=$!
sleep 3

if ifconfig 2>/dev/null | grep -q "198.18.0.1"; then
  pass "utun 设备已创建 (198.18.0.1) —— TUN 真正以 root 启动成功！"
  RESULT=0
else
  fail "utun 设备未出现 —— 启动失败"
  RESULT=1
fi

# 停掉测试进程
sudo -n /usr/bin/pkill -f "tun-adapter" 2>/dev/null
kill "$DIAG_PID" 2>/dev/null
wait "$DIAG_PID" 2>/dev/null

echo
echo "  —— tun-adapter 输出（最后 15 行）——"
tail -15 "$SCRIPT_DIR/.diag-tun.log" 2>/dev/null | sed 's/^/      /'

echo
echo "============================================================"
if [ "$RESULT" = "0" ]; then
  echo " 结论：免密 sudo + TUN 启动链路正常。"
  echo "       现在请在【同一个】Terminal.app 里启动 dashboard："
  echo "         node server.js"
else
  echo " 结论：TUN 未能以 root 启动。请看上面第 [4]/[5] 步的错误。"
  echo "       最常见原因：当前不是系统 Terminal.app，sudo 被沙箱拦截。"
fi
echo "============================================================"
exit "$RESULT"
