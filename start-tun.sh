#!/bin/bash
# ============================================================
# TUN 模式一键启动脚本
#
# 启动顺序：proxy-local（带 DNS bypass 参数） → tun-adapter
# 停止：Ctrl+C 会同时终止两个进程并恢复系统路由/DNS
# ============================================================

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"

# === 配置 ===
# proxy-local 直连用的 DNS 服务器（必须和 tun.toml 中 dns_bypass_ips 一致）
# 顺序兜底：优先用稳定的 114，114 不可用时才轮到 223 。
DNS_SERVERS="114.114.114.114"
DNS_FALLBACK="223.5.5.5"

# proxy-local 的 JVM DNS 参数
# 使用 Netty 内置 DNS resolver（系统属性 proxy.dns.nameservers）
JVM_DNS_OPTS="-Dproxy.dns.nameservers=${DNS_SERVERS},${DNS_FALLBACK}"

# ============================================================

echo "=== SimplePlanePlatform TUN Mode ==="
echo ""

# 检查上次是否异常退出（备份文件存在 = 上次 DNS 没有恢复）
DNS_BACKUP="/tmp/tun-adapter-dns-backup.conf"
if [ -f "$DNS_BACKUP" ]; then
    echo "WARNING: Detected unclean shutdown from last run!"
    echo "         Restoring DNS settings before starting..."
    sudo "$SCRIPT_DIR/restore-dns.sh"
    echo ""
fi

echo "DNS bypass servers: ${DNS_SERVERS}, ${DNS_FALLBACK}"
echo ""

# 清理函数：Ctrl+C 时同时杀掉两个子进程
cleanup() {
    echo ""
    echo "[TUN] Shutting down..."
    # tun-adapter 以 sudo 运行，需要 sudo kill 才能发送信号
    # 发送 SIGTERM 让它优雅退出（触发 Drop 恢复路由/DNS）
    if [ -n "$TUN_PID" ]; then
        sudo kill "$TUN_PID" 2>/dev/null || true
        # 给它几秒完成清理
        for i in $(seq 1 5); do
            if ! sudo kill -0 "$TUN_PID" 2>/dev/null; then
                break
            fi
            sleep 1
        done
        # 如果还没退出，强制杀（Drop 不会执行）
        if sudo kill -0 "$TUN_PID" 2>/dev/null; then
            echo "[TUN] Force killing tun-adapter (graceful shutdown failed)..."
            sudo kill -9 "$TUN_PID" 2>/dev/null || true
        fi
    fi
    if [ -n "$PROXY_PID" ]; then
        kill "$PROXY_PID" 2>/dev/null || true
        wait "$PROXY_PID" 2>/dev/null || true
    fi

    # 兜底：如果备份文件还在（说明 Drop 没有成功恢复），手动执行恢复
    if [ -f "$DNS_BACKUP" ]; then
        echo "[TUN] Drop cleanup did not complete, running manual restore..."
        sudo "$SCRIPT_DIR/restore-dns.sh"
    fi

    echo "[TUN] All processes stopped."
}
trap cleanup EXIT INT TERM

# Step 1: 启动 proxy-local（带 DNS bypass JVM 参数）
PROXY_JAR="proxy-local/target/proxy-local-1.0.0-SNAPSHOT.jar"
if [ ! -f "$PROXY_JAR" ]; then
    echo "     Building proxy-local fat jar..."
    mvn -pl proxy-local -am package -DskipTests -q
fi
echo "[1/2] Starting proxy-local with DNS bypass..."
java ${JVM_DNS_OPTS} -jar "$PROXY_JAR" &
PROXY_PID=$!

# 等待 proxy-local 启动（监听 1080 端口）
echo "     Waiting for proxy-local to be ready..."
for i in $(seq 1 30); do
    if lsof -i :1080 -sTCP:LISTEN >/dev/null 2>&1; then
        echo "     proxy-local is ready (port 1080)"
        break
    fi
    if ! kill -0 "$PROXY_PID" 2>/dev/null; then
        echo "ERROR: proxy-local exited unexpectedly"
        exit 1
    fi
    sleep 1
done

if ! lsof -i :1080 -sTCP:LISTEN >/dev/null 2>&1; then
    echo "ERROR: proxy-local failed to start within 30s"
    exit 1
fi

# Step 2: 编译并启动 tun-adapter（需要 root 权限创建 TUN 设备）
cd tun-adapter
TUN_BIN="$(pwd)/target/release/tun-adapter"

# 如果 binary 不存在或源码比 binary 新，则重新编译
if [ ! -f "$TUN_BIN" ] || [ "$(find src -name '*.rs' -newer "$TUN_BIN" 2>/dev/null | head -1)" ]; then
    echo "[2/2] Building tun-adapter..."
    # 确保 cargo 可用（nvm/rustup 可能不在 sudo 路径中）
    if [ -f "$HOME/.cargo/env" ]; then
        source "$HOME/.cargo/env"
    fi
    if ! command -v cargo &>/dev/null; then
        echo "ERROR: cargo not found. Please install Rust: https://rustup.rs"
        exit 1
    fi
    cargo build --release 2>&1 | tail -3
    if [ ! -f "$TUN_BIN" ]; then
        echo "ERROR: tun-adapter build failed"
        exit 1
    fi
else
    echo "[2/2] tun-adapter binary is up-to-date, skipping build"
fi
echo "     Starting tun-adapter (requires sudo)..."
TUN_CONFIG="$(pwd)/config/tun.toml"
cd "$SCRIPT_DIR"

# 提前请求 sudo 权限（交互式输入密码），避免后台进程无法提示
sudo -v

# 启动 tun-adapter（前台 sudo 让信号能正确传递）
sudo "$TUN_BIN" -c "$TUN_CONFIG" &
TUN_PID=$!

# 等待 tun-adapter 实际启动（检查 utun 设备出现）
echo "     Waiting for TUN device..."
for i in $(seq 1 10); do
    if ifconfig utun9 >/dev/null 2>&1; then
        echo "     TUN device ready (utun9)"
        break
    fi
    if ! sudo kill -0 "$TUN_PID" 2>/dev/null; then
        echo "ERROR: tun-adapter exited unexpectedly"
        echo "       Check tun-adapter.log for details"
        exit 1
    fi
    sleep 1
done

echo ""
echo "=== TUN mode is active ==="
echo "    proxy-local PID: $PROXY_PID"
echo "    tun-adapter PID: $TUN_PID (sudo)"
echo "    Press Ctrl+C to stop"
echo "    If something goes wrong: sudo ./restore-dns.sh"
echo ""

# 等待任一进程退出
wait "$PROXY_PID" 2>/dev/null || true
