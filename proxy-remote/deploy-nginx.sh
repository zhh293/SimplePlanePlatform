#!/bin/bash
# 远程服务器一键部署脚本
# 用法: ssh -i <your-key.pem> <user>@<your-server-ip> 'bash -s' < proxy-remote/deploy-nginx.sh

set -e

echo "=== 1. 配置 nginx stream 代理 ==="

# nginx 的 stream 块需要放在主配置的顶层（与 http 块平级），不能放在 conf.d 里
# 先检查是否已经有 stream 配置
if grep -q "stream {" /etc/nginx/nginx.conf; then
    echo "stream 块已存在，跳过..."
else
    # 将 stream 块追加到 nginx.conf 末尾（与 http 块平级）
    sudo tee -a /etc/nginx/nginx.conf > /dev/null <<'EOF'

# TCP Layer-4 reverse proxy for proxy-remote Netty server
stream {
    log_format proxy '$remote_addr [$time_local] '
                     '$protocol $status $bytes_sent $bytes_received '
                     '$session_time "$upstream_addr"';

    access_log /var/log/nginx/proxy-remote-access.log proxy;
    error_log  /var/log/nginx/proxy-remote-error.log;

    upstream netty_backend {
        server 127.0.0.1:19090;
    }

    server {
        listen 9090;
        proxy_pass netty_backend;
        proxy_connect_timeout 5s;
        proxy_timeout 600s;
        proxy_protocol off;
    }
}
EOF
    echo "stream 块已添加到 /etc/nginx/nginx.conf"
fi

echo "=== 2. 验证 nginx 配置 ==="
sudo nginx -t

echo "=== 3. 重启 nginx ==="
sudo systemctl restart nginx
sudo systemctl enable nginx

echo "=== 4. 停止旧的 proxy-remote 服务 ==="
pkill -f "proxy-remote" 2>/dev/null || true
sleep 2

echo "=== 5. 启动新的 proxy-remote 服务（监听 127.0.0.1:19090）==="
cd ~
nohup java -jar proxy-remote-1.0.0-SNAPSHOT.jar > proxy-remote.log 2>&1 &
sleep 3

echo "=== 6. 验证服务状态 ==="
echo "--- Netty 进程 ---"
ps aux | grep proxy-remote | grep -v grep

echo "--- 端口监听 ---"
ss -tlnp | grep -E '9090|19090'

echo ""
echo "✓ 部署完成！"
echo "  Netty 监听: 127.0.0.1:19090（仅本地）"
echo "  Nginx 监听: 0.0.0.0:9090（对外代理）"
echo "  客户端连接地址不变: <your-server-ip>:9090"
