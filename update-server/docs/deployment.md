# Update Server 部署指南

## 单机部署（推荐）

### 1. 系统准备

```bash
# Ubuntu/Debian
sudo apt update && sudo apt install -y nodejs
# 安装 Node 22+ 或 24+：用 nvm 或 nodesource
curl -fsSL https://deb.nodesource.com/setup_22.x | sudo -E bash -
sudo apt install -y nodejs

# 验证
node --version  # v22.x 或 v24.x
```

### 2. 部署代码

```bash
sudo mkdir -p /opt/simpleplane-update
sudo chown -R $USER:$USER /opt/simpleplane-update
cd /opt/simpleplane-update
git clone <repo> .
cd update-server
```

### 3. 生成密钥和配置

```bash
# 生成 session secret（≥32 字节）
openssl rand -hex 32

# 生成 SRP verifier（管理员密码）
cd /opt/simpleplane-update/update-server
node --experimental-sqlite scripts/generate-srp-credentials.mjs > /tmp/verifier.json
# 输入两次密码（12+ 字符，含大小写+数字+符号），脚本会在屏幕上显示 *

# 安装 verifier 文件（严格权限）
sudo install -m 0400 -o simpleplane -g simpleplane /tmp/verifier.json /etc/simpleplane-update/verifier
rm /tmp/verifier.json

# 写到 /etc/simpleplane-update.env
sudo tee /etc/simpleplane-update.env > /dev/null <<EOF
HOST=127.0.0.1
PORT=8787
DATA_DIR=/opt/simpleplane-update/data
PUBLIC_BASE_URL=https://update.example.com
SESSION_SECRET=<上一步生成的 hex>
MAX_FILE_SIZE=1073741824
MAX_CHUNK_SIZE=33554432
DEFAULT_CHUNK_SIZE=8388608
UPLOAD_SESSION_TTL_HOURS=24
MAX_ACTIVE_UPLOADS=5
# 注意：ADMIN_PASSWORD_HASH 已废弃，verifier 走 systemd LoadCredential 注入
EOF
sudo chmod 600 /etc/simpleplane-update.env
sudo chown root:root /etc/simpleplane-update.env
```

> 密码强度策略：脚本强制 12+ 字符、含大小写+数字+符号、不在弱密码字典。`--insecure-weak` 可绕过但会在审计日志里标记。

### 4. systemd 服务

`/etc/systemd/system/simpleplane-update.service`：

```ini
[Unit]
Description=SimplePlane Update Server
After=network.target

[Service]
Type=simple
User=simpleplane
Group=simpleplane
WorkingDirectory=/opt/simpleplane-update/update-server
EnvironmentFile=/etc/simpleplane-update.env
ExecStart=/usr/bin/node --experimental-sqlite src/server.js

# 注入 SRP verifier 到进程私有 namespace（${CREDENTIALS_DIRECTORY}）
# 磁盘上的 verifier 文件 root 才能读，进程通过 systemd 注入访问。
LoadCredential=srp_verifier:/etc/simpleplane-update/verifier
Environment=SRP_VERIFIER_FILE=%d/srp_verifier

# 沙盒
ProtectSystem=strict
ProtectHome=yes
PrivateTmp=yes
NoNewPrivileges=yes
CapabilityBoundingSet=
RestrictAddressFamilies=AF_INET AF_INET6
SystemCallFilter=@system-service
SystemCallFilter=~@privileged

# 资源限制
LimitNOFILE=1024
LimitNPROC=64

[Install]
WantedBy=multi-user.target
```

```bash
sudo useradd -r -s /usr/sbin/nologin -d /opt/simpleplane-update simpleplane
sudo chown -R simpleplane:simpleplane /opt/simpleplane-update
sudo systemctl daemon-reload
sudo systemctl enable --now simpleplane-update
sudo systemctl status simpleplane-update
# 验证 verifier 已加载（日志应该显示 "SRP verifier loaded for user admin"）
sudo journalctl -u simpleplane-update --since '1 minute ago' | head -10
```

### 5. Nginx 反向代理 + HTTPS

`/etc/nginx/sites-available/simpleplane-update`：

```nginx
server {
    listen 80;
    server_name update.example.com;
    return 301 https://$host$request_uri;
}

server {
    listen 443 ssl http2;
    server_name update.example.com;

    ssl_certificate /etc/letsencrypt/live/update.example.com/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/update.example.com/privkey.pem;
    ssl_protocols TLSv1.2 TLSv1.3;

    # 上传分片最大 32 MiB + 余量
    client_max_body_size 40m;

    # /download/* 长缓存
    location /download/ {
        proxy_pass http://127.0.0.1:8787;
        proxy_set_header Host $host;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_cache_valid 200 1y;
        add_header Cache-Control "public, max-age=31536000, immutable";
    }

    # /api/v1/updates/* 不缓存
    location /api/v1/ {
        proxy_pass http://127.0.0.1:8787;
        proxy_set_header Host $host;
        proxy_set_header X-Forwarded-Proto $scheme;
        add_header Cache-Control "no-store";
    }

    # /latest.json 不缓存
    location = /latest.json {
        proxy_pass http://127.0.0.1:8787;
        proxy_set_header Host $host;
        add_header Cache-Control "no-store";
    }

    # 管理面板
    location /admin/ {
        proxy_pass http://127.0.0.1:8787;
        proxy_set_header Host $host;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_set_header X-Forwarded-For $remote_addr;
    }
    location = /admin { return 301 /admin/; }

    # /healthz
    location = /healthz {
        proxy_pass http://127.0.0.1:8787;
        access_log off;
    }

    location / {
        return 404;
    }
}
```

```bash
sudo ln -s /etc/nginx/sites-available/simpleplane-update /etc/nginx/sites-enabled/
sudo nginx -t
sudo systemctl reload nginx

# Let's Encrypt 证书
sudo apt install -y certbot python3-certbot-nginx
sudo certbot --nginx -d update.example.com
```

### 6. 防火墙

```bash
sudo ufw allow 22/tcp
sudo ufw allow 80/tcp
sudo ufw allow 443/tcp
sudo ufw enable
```

## 备份策略

### 必备份

- `data/update-server.db`（SQLite，所有版本元数据 + 审计日志）
- Tauri 私钥（不在本服务，在 CI Secrets 或管理员本地）

### 推荐备份

- `data/releases/{platform}/{version}/*.zip` 和 `.sig`——可在 CI 重新构建，但备份可省去重新签名和重新构建。

### 备份脚本

```bash
# /opt/simpleplane-update/backup.sh
#!/bin/bash
set -e
BACKUP_DIR=/var/backups/simpleplane-update
mkdir -p "$BACKUP_DIR"
DATE=$(date -u +%Y%m%dT%H%M%SZ)
# 使用 sqlite3 的 .backup API（或 cp 在 WAL checkpoint 之后）
sqlite3 /opt/simpleplane-update/data/update-server.db ".backup '$BACKUP_DIR/update-server-$DATE.db'"
# 同步 releases 目录
rsync -a /opt/simpleplane-update/data/releases/ "$BACKUP_DIR/releases-$DATE/"
# 保留最近 30 天
find "$BACKUP_DIR" -mtime +30 -delete
```

crontab：

```cron
0 3 * * * /opt/simpleplane-update/backup.sh
```

## 升级流程

```bash
cd /opt/simpleplane-update/update-server
git pull --ff-only
# 测试
node --experimental-sqlite --test tests/*.test.mjs
# 重启
sudo systemctl restart simpleplane-update
# 健康检查
curl -s https://update.example.com/healthz
```

## 故障排查

- **`node:sqlite` 不可用**：检查 Node 版本 ≥ 22，启动参数带 `--experimental-sqlite`。
- **登录失败 503 `srp_not_configured`**：systemd `LoadCredential=srp_verifier:...` 路径不对，或 verifier 文件权限不是 simpleplane 可读。检查 `journalctl -u simpleplane-update` 是否有 "SRP verifier loaded" 日志。
- **登录失败 410 `legacy_login_deprecated`**：旧 `/admin/login` 已废弃。前端会自动走 SRP；若手动 curl 测试，改用 SRP 客户端流程（参考 `scripts/e2e-test.mjs` 的 `srpLogin` 函数）。
- **登录失败 401 `invalid_credentials`**：密码错；前端 SRP 握手算出的 M1 与服务端不匹配。检查 CLI 生成 verifier 时用的密码是否就是登录时输的。
- **登录 429 `too_many_attempts`**：5 次失败后 IP 锁定 15 分钟。重启服务可清空内存锁定状态。
- **上传 413**：分片大小超过 `MAX_CHUNK_SIZE`（默认 32 MiB），或 Nginx `client_max_body_size` 不足。
- **下载 404**：检查 `data/releases/{platform}/{version}/` 目录文件是否存在；SQLite 中该版本 `is_latest=1`。
- **客户端拉到旧版本**：检查 Tauri endpoint URL 是否包含 `{{target}}` `{{arch}}` `{{current_version}}` 占位符；`PUBLIC_BASE_URL` 是否在服务端正确配置。
- **verifier 轮换后旧 session 仍有效**：检查 `ctx.sessionSecret` 是否在轮换后也被重新生成（应被重新生成，所有旧 cookie 失效）。

## 性能调优

- **SQLite WAL 模式**：默认开启，多读单写，适合单实例。
- **下载流量大**：`/download/*` 前置 CDN（Cloudflare 阿里云）。`PUBLIC_BASE_URL` 改为 CDN 域名。
- **上传慢**：调高 `DEFAULT_CHUNK_SIZE` 到 16 MiB 或 32 MiB（更少 round-trip）。
- **并发上传多**：调高 `MAX_ACTIVE_UPLOADS`，但注意每分片最大 32 MiB 内存占用。
- **SRP 握手慢**：2048-bit modPow 在浏览器约 50-200ms，服务端类似。如果觉得慢，可降级到 1024-bit group（不推荐，安全降低）。

## 安全清单

- [ ] `ADMIN_PASSWORD_HASH` 字段已从 `.env` 移除
- [ ] `/etc/simpleplane-update/verifier` 权限 0400，owner=simpleplane
- [ ] systemd `LoadCredential=srp_verifier` 配置生效（journalctl 显示 "SRP verifier loaded"）
- [ ] `SESSION_SECRET` ≥ 32 字节随机
- [ ] HTTPS 证书有效
- [ ] Nginx 限制 `/admin/` 来源 IP（可选，加 `allow/deny`）
- [ ] Tauri 私钥不在服务器（只在 CI Secrets）
- [ ] 防火墙只开 22/80/443
- [ ] systemd 服务用户无 shell（`/usr/sbin/nologin`）
- [ ] systemd 沙盒指令（`ProtectSystem=strict` `NoNewPrivileges` `CapabilityBoundingSet=`）
- [ ] 备份定期运行
- [ ] 审计日志定期 review（`GET /admin/audit`）
- [ ] 管理员密码 12+ 字符、含大小写+数字+符号
- [ ] verifier 定期轮换（建议每 90 天，控制面板"rotate verifier"入口）
