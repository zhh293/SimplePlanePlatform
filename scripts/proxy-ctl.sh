#!/bin/bash
# ============================================================
# proxy-ctl.sh —— 统一的本地代理 / TUN 模式管理脚本
#
# 解决的问题：
#   系统代理模式与 TUN 模式频繁切换时，常因 proxy-local 进程残留而冲突——
#   TUN 模式给 proxy-local 注入了 -Dproxy.dns.nameservers=114.114.114.114
#   （单点 DNS、无 failover、resolver 进程级复用），切回系统代理模式时若
#   不重启进程，直连流量仍被迫走 114；一旦该进程内 DNS resolver 卡死，
#   所有走直连分流的国内站点全部解析失败。
#
# 根治思路（不改任何代理核心代码，纯运维封装）：
#   1. 两种模式互斥：任何 start 操作前，先彻底停掉已有 proxy-local / tun-adapter，
#      消除残留进程与残留 DNS 参数。
#   2. 各用对的 DNS 策略：
#        - 系统代理模式 (proxy)：proxy-local 不带 -Dproxy.dns.nameservers，
#          直连走系统默认 DNS（即你在系统设置里配的 DNS）。
#        - TUN 模式 (tun)：proxy-local 带 114/223（与 tun.toml dns_bypass_ips 一致），
#          因为 TUN 下系统 DNS 被 FakeDNS 劫持，必须用被放行的真实 DNS。
#   3. 退出 TUN 时还原系统路由 / DNS（复用 restore-dns.sh）。
#
# 用法：
#   scripts/proxy-ctl.sh start-proxy   # 启动系统代理模式（用系统 DNS）
#   scripts/proxy-ctl.sh start-tun     # 启动 TUN 模式（用 114/223 DNS）
#   scripts/proxy-ctl.sh stop          # 停止全部并清理
#   scripts/proxy-ctl.sh status        # 查看当前状态
#   scripts/proxy-ctl.sh switch-proxy  # = stop + start-proxy（一键切到系统代理）
#   scripts/proxy-ctl.sh switch-tun    # = stop + start-tun  （一键切到 TUN）
# ============================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
cd "$REPO_ROOT"

# === 配置 ===
LOCAL_PORT=1080
PROXY_JAR="${REPO_ROOT}/proxy-local/target/proxy-local-1.0.0-SNAPSHOT.jar"
TUN_BIN="${REPO_ROOT}/tun-adapter/target/release/tun-adapter"
TUN_CONFIG="${REPO_ROOT}/tun-adapter/config/tun.toml"
RESTORE_DNS="${REPO_ROOT}/restore-dns.sh"
DNS_BACKUP="/tmp/tun-adapter-dns-backup.conf"

# TUN 模式下 proxy-local 的直连 DNS（必须与 tun.toml dns_bypass_ips 一致）
TUN_DNS="114.114.114.114,223.5.5.5"

# 运行态标记：记录当前以何种模式启动，便于 status / stop 判断
STATE_FILE="/tmp/proxy-ctl.mode"
PROXY_LOG="${REPO_ROOT}/logs/proxy-local.log"

# ---- 工具函数 ----------------------------------------------------------------

c_green()  { printf '\033[32m%s\033[0m\n' "$*"; }
c_yellow() { printf '\033[33m%s\033[0m\n' "$*"; }
c_red()    { printf '\033[31m%s\033[0m\n' "$*"; }

# 找出所有 proxy-local 进程 PID（通过 jar 名匹配，避免误杀其它 java）
find_proxy_pids() {
    pgrep -f "proxy-local-1.0.0-SNAPSHOT.jar" 2>/dev/null || true
}

# 找出所有 tun-adapter 进程 PID
find_tun_pids() {
    pgrep -f "tun-adapter/target/release/tun-adapter" 2>/dev/null || true
}

port_in_use() {
    lsof -nP -iTCP:"${LOCAL_PORT}" -sTCP:LISTEN >/dev/null 2>&1
}

# 彻底停止所有相关进程 + 还原 DNS。这是“互斥”的核心保证。
stop_all() {
    local quiet="${1:-}"
    [ "$quiet" = "quiet" ] || echo "[stop] 停止已有 proxy-local / tun-adapter 进程..."

    # 1. 先停 tun-adapter（setuid root，普通 kill 即可发 SIGTERM 触发其 Drop 清理）
    local tpids
    tpids="$(find_tun_pids)"
    if [ -n "$tpids" ]; then
        for p in $tpids; do
            kill "$p" 2>/dev/null || sudo kill "$p" 2>/dev/null || true
        done
        # 给它最多 6s 完成路由/DNS 还原
        for _ in $(seq 1 6); do
            [ -z "$(find_tun_pids)" ] && break
            sleep 1
        done
        # 仍未退出则强杀（此时 Drop 可能没跑，下面靠 restore-dns.sh 兜底）
        tpids="$(find_tun_pids)"
        [ -n "$tpids" ] && for p in $tpids; do sudo kill -9 "$p" 2>/dev/null || true; done
    fi

    # 2. 停 proxy-local
    local ppids
    ppids="$(find_proxy_pids)"
    if [ -n "$ppids" ]; then
        for p in $ppids; do kill "$p" 2>/dev/null || true; done
        for _ in $(seq 1 6); do
            [ -z "$(find_proxy_pids)" ] && break
            sleep 1
        done
        ppids="$(find_proxy_pids)"
        [ -n "$ppids" ] && for p in $ppids; do kill -9 "$p" 2>/dev/null || true; done
    fi

    # 3. 兜底还原 DNS：只要备份文件还在，说明 TUN 没干净退出，必须还原
    if [ -f "$DNS_BACKUP" ]; then
        [ "$quiet" = "quiet" ] || c_yellow "[stop] 检测到 DNS 备份残留，执行 restore-dns.sh 还原..."
        sudo "$RESTORE_DNS" || true
    fi

    rm -f "$STATE_FILE"
    [ "$quiet" = "quiet" ] || c_green "[stop] 已全部停止并清理。"
}

# 启动 proxy-local；参数 $1 = "system" | "tun"，决定是否注入 114/223 DNS
start_proxy_local() {
    local dns_mode="$1"
    local jvm_opts=""
    if [ "$dns_mode" = "tun" ]; then
        jvm_opts="-Dproxy.dns.nameservers=${TUN_DNS}"
        echo "[proxy-local] DNS 策略：TUN 专用 ${TUN_DNS}（与 tun.toml dns_bypass_ips 对齐）"
    else
        echo "[proxy-local] DNS 策略：系统默认 DNS（不注入 proxy.dns.nameservers）"
    fi

    if [ ! -f "$PROXY_JAR" ]; then
        echo "[proxy-local] 未找到 jar，开始构建..."
        mvn -pl proxy-local -am package -DskipTests -q
    fi

    # nohup 后台运行，日志走项目自身 logback 配置
    # shellcheck disable=SC2086
    nohup java $jvm_opts -jar "$PROXY_JAR" >/dev/null 2>&1 &
    local pid=$!

    echo "[proxy-local] 启动中 (PID=$pid)，等待监听 ${LOCAL_PORT} ..."
    for _ in $(seq 1 30); do
        port_in_use && { c_green "[proxy-local] 就绪 (端口 ${LOCAL_PORT})"; return 0; }
        kill -0 "$pid" 2>/dev/null || { c_red "[proxy-local] 启动失败，进程已退出"; return 1; }
        sleep 1
    done
    c_red "[proxy-local] 30s 内未就绪"; return 1
}

# ---- 子命令 ------------------------------------------------------------------

cmd_start_proxy() {
    if [ -n "$(find_proxy_pids)$(find_tun_pids)" ]; then
        c_yellow "检测到已有进程，先执行互斥停止..."
        stop_all
    fi
    echo "=== 启动【系统代理模式】 ==="
    start_proxy_local "system"
    echo "system" > "$STATE_FILE"
    echo
    c_green "系统代理模式已启动。请把系统/浏览器代理指向 127.0.0.1:${LOCAL_PORT}"
    echo "直连流量使用系统 DNS，不会再被 114 残留拖垮。"
}

cmd_start_tun() {
    if [ -n "$(find_proxy_pids)$(find_tun_pids)" ]; then
        c_yellow "检测到已有进程，先执行互斥停止..."
        stop_all
    fi
    echo "=== 启动【TUN 模式】 ==="

    # 上次异常退出残留 DNS 备份则先还原
    if [ -f "$DNS_BACKUP" ]; then
        c_yellow "检测到上次未清理的 DNS 备份，先还原..."
        sudo "$RESTORE_DNS" || true
    fi

    start_proxy_local "tun"

    if [ ! -x "$TUN_BIN" ]; then
        c_red "未找到 tun-adapter 可执行文件：$TUN_BIN"
        c_red "请先用 start-tun.sh 或 cargo build --release 构建。"
        return 1
    fi

    echo "[tun-adapter] 启动中 (需要 root 权限)..."
    sudo -v
    sudo "$TUN_BIN" -c "$TUN_CONFIG" >/dev/null 2>&1 &
    echo "tun" > "$STATE_FILE"

    echo "[tun-adapter] 等待 TUN 设备 (utun9) ..."
    for _ in $(seq 1 10); do
        ifconfig utun9 >/dev/null 2>&1 && { c_green "[tun-adapter] TUN 设备就绪 (utun9)"; break; }
        sleep 1
    done
    echo
    c_green "TUN 模式已启动（全局透明代理）。停止请用：scripts/proxy-ctl.sh stop"
}

cmd_status() {
    echo "=== proxy-ctl 状态 ==="
    local mode="未知"
    [ -f "$STATE_FILE" ] && mode="$(cat "$STATE_FILE")"
    echo "记录模式 : $mode"

    local ppids tpids
    ppids="$(find_proxy_pids)"; tpids="$(find_tun_pids)"
    if [ -n "$ppids" ]; then c_green "proxy-local: 运行中 (PID: $(echo $ppids | tr '\n' ' '))"; else c_red "proxy-local: 未运行"; fi
    if [ -n "$tpids" ]; then c_green "tun-adapter: 运行中 (PID: $(echo $tpids | tr '\n' ' '))"; else echo "tun-adapter: 未运行"; fi

    if port_in_use; then c_green "端口 ${LOCAL_PORT}: 监听中"; else c_red "端口 ${LOCAL_PORT}: 未监听"; fi

    # 从日志判断 proxy-local 当前实际用的 DNS 策略
    if [ -f "$PROXY_LOG" ]; then
        local last_dns
        last_dns="$(grep -a "DirectRelay using custom DNS resolver" "$PROXY_LOG" 2>/dev/null | tail -1 || true)"
        if [ -n "$last_dns" ]; then
            c_yellow "日志显示 proxy-local 启用了自定义 DNS（TUN 专用）：${last_dns##*resolver: }"
            [ "$mode" = "system" ] && c_red "  ⚠ 当前标记为系统代理模式，却带着 TUN 的 DNS —— 建议 switch-proxy 重启消除冲突"
        else
            echo "日志显示 proxy-local 使用系统 DNS（系统代理模式正常）"
        fi
    fi

    [ -f "$DNS_BACKUP" ] && c_yellow "⚠ 存在 DNS 备份文件 $DNS_BACKUP（TUN 可能未干净退出）"
    echo
    echo "当前系统 DNS："
    scutil --dns 2>/dev/null | awk '/nameserver\[/{print "  " $0}' | sort -u | head -6
}

usage() {
    cat <<EOF
用法: scripts/proxy-ctl.sh <command>

  start-proxy    启动系统代理模式（直连走系统 DNS，不带 114）
  start-tun      启动 TUN 模式（直连走 114/223，与 tun.toml 对齐）
  stop           停止全部进程并还原 DNS/路由
  status         查看运行状态与 DNS 策略一致性
  switch-proxy   一键切到系统代理模式（先 stop 再 start-proxy）
  switch-tun     一键切到 TUN 模式（先 stop 再 start-tun）

核心保证：任何 start/switch 都会先彻底停掉旧进程，两种模式永不共用残留进程，
从根上杜绝“切回系统代理后仍被 TUN 的 114 DNS 拖垮”的冲突。
EOF
}

case "${1:-}" in
    start-proxy)   cmd_start_proxy ;;
    start-tun)     cmd_start_tun ;;
    stop)          stop_all ;;
    status)        cmd_status ;;
    switch-proxy)  stop_all quiet; cmd_start_proxy ;;
    switch-tun)    stop_all quiet; cmd_start_tun ;;
    -h|--help|help|"") usage ;;
    *) c_red "未知命令: $1"; echo; usage; exit 1 ;;
esac
