#!/bin/bash
# ============================================================
# TUN 模式 DNS 恢复脚本
#
# 用途：当 tun-adapter 异常退出（kill -9、崩溃、终端关闭）后，
#       系统 DNS 和 /etc/resolver/ 文件没有被自动恢复时使用。
#
# 用法：sudo ./restore-dns.sh
# ============================================================

set -e

BACKUP_FILE="/tmp/tun-adapter-dns-backup.conf"

echo "=== TUN DNS Recovery ==="
echo ""

# 检查是否有备份文件
if [ ! -f "$BACKUP_FILE" ]; then
    echo "No backup file found at $BACKUP_FILE"
    echo "This means either:"
    echo "  - TUN adapter was never started"
    echo "  - TUN adapter exited normally and already restored DNS"
    echo ""
    echo "Current DNS settings:"
    # 尝试检测活跃网络服务
    SERVICE=$(networksetup -listallnetworkservices | grep -v "^An asterisk" | grep -v "^\*" | head -1)
    networksetup -getdnsservers "$SERVICE" 2>/dev/null || echo "  (unable to detect)"
    echo ""
    echo "If DNS is still broken, you can manually fix:"
    echo "  sudo networksetup -setdnsservers Wi-Fi Empty"
    echo "  (or replace 'Wi-Fi' with your network service name)"
    exit 0
fi

echo "Found backup file: $BACKUP_FILE"
echo "Contents:"
cat "$BACKUP_FILE"
echo ""
echo "---"

# 解析备份文件
SERVICE_NAME=""
DNS_SERVERS=()
RESOLVER_FILES=()

while IFS= read -r line; do
    if [[ "$line" == service:* ]]; then
        SERVICE_NAME="${line#service:}"
    elif [[ "$line" == dns:* ]]; then
        DNS_SERVERS+=("${line#dns:}")
    elif [[ "$line" == resolver:* ]]; then
        RESOLVER_FILES+=("${line#resolver:}")
    fi
done < "$BACKUP_FILE"

if [ -z "$SERVICE_NAME" ]; then
    echo "ERROR: Could not parse service name from backup file"
    exit 1
fi

echo "Network service: $SERVICE_NAME"
echo "Original DNS: ${DNS_SERVERS[*]}"
echo "Resolver files to remove: ${#RESOLVER_FILES[@]}"
echo ""

# Step 1: 删除 /etc/resolver/ 文件
if [ ${#RESOLVER_FILES[@]} -gt 0 ]; then
    echo "[1/3] Removing /etc/resolver/ files..."
    for f in "${RESOLVER_FILES[@]}"; do
        if [ -f "$f" ]; then
            rm -f "$f"
            echo "  Removed: $f"
        else
            echo "  Already gone: $f"
        fi
    done
    # 如果目录为空则删除
    if [ -d "/etc/resolver" ] && [ -z "$(ls -A /etc/resolver 2>/dev/null)" ]; then
        rmdir /etc/resolver
        echo "  Removed empty /etc/resolver/ directory"
    fi
else
    echo "[1/3] No resolver files to remove"
fi

# Step 2: 恢复 DNS
# 说明：macOS 只接受 "Empty" 来把 DNS 重置为「自动获取」。
#       "DHCP" 不是合法参数（旧版本误写入备份文件，会导致
#       "DHCP is not a valid IP address" 报错），这里向后兼容地
#       把 "Empty" / "DHCP" / 空 都视为「恢复成自动获取」。
echo "[2/3] Restoring DNS..."
if [ ${#DNS_SERVERS[@]} -eq 0 ] || \
   { [ ${#DNS_SERVERS[@]} -eq 1 ] && { [ "${DNS_SERVERS[0]}" == "Empty" ] || [ "${DNS_SERVERS[0]}" == "DHCP" ]; }; }; then
    echo "  Setting DNS to automatic (Empty)..."
    networksetup -setdnsservers "$SERVICE_NAME" Empty
else
    echo "  Setting DNS to: ${DNS_SERVERS[*]}"
    networksetup -setdnsservers "$SERVICE_NAME" "${DNS_SERVERS[@]}"
fi

# Step 3: 刷新 DNS 缓存
echo "[3/3] Flushing DNS cache..."
dscacheutil -flushcache
killall -HUP mDNSResponder 2>/dev/null || true

# 删除备份文件
rm -f "$BACKUP_FILE"

echo ""
echo "=== DNS Restored Successfully ==="
echo ""
echo "Current DNS settings:"
networksetup -getdnsservers "$SERVICE_NAME"
