# SimplePlane 节点订阅服务

订阅服务负责账号授权、设备绑定、节点分组和配置下发。管理员给账号分配节点组、有效期和设备名额。用户用客户端确认设备后，就能同步所在组已上线的 HTTP/3 节点。

## 认证中心与访问入口

[AuthSystemSimple](https://github.com/zhh293/AuthSystemSimple) 是独立的 OAuth 2.0 / OpenID Connect 认证服务。它负责登录、会话与令牌签发。SimplePlane 通过其 Spring Boot SDK 使用授权码流程和 PKCE，以 OIDC `issuer` 与 `sub` 识别用户，订阅权限则由本模块管理。[AuthSystemSimple SDK 接入指南](https://github.com/zhh293/AuthSystemSimple/blob/e1d8fc0/docs/接入指南.md)

| 入口 | 地址 |
| --- | --- |
| 认证中心 | [AuthSystemSimple 登录页](https://212.64.14.155/login) |
| OIDC issuer 与发现文档 | `https://212.64.14.155` · [Discovery JSON](https://212.64.14.155/.well-known/openid-configuration) |
| 订阅管理 | [运营后台](https://212.64.14.155/admin/grants) |
| 用户设备 | [我的设备](https://212.64.14.155/account/devices) |

当前实例使用私有 CA 签发的 IP 证书。访问上述入口的浏览器和客户端需信任该 CA。向只使用系统默认信任根的设备交付时，入口需部署这些设备信任的 HTTPS 证书。

## 管理员和用户怎么用

1. 管理员在「节点分组」中选择节点，在「用户管理」中创建或查找用户，再设置节点组、订阅有效期和设备名额。同一节点可以加入多个组，每份用户订阅对应一个组。
2. 用户在支持订阅的客户端选择「登录并同步节点」，用系统浏览器登录并确认设备。桌面客户端也能显示二维码，供手机扫码确认。
3. 客户端使用绑定密钥签名请求，取得节点地址、TLS 信任材料和短期设备证书。组内节点调整会在下一次成功同步时生效。退出订阅保留手动节点。
4. 管理员可在用户详情停止订阅或解绑单台设备。后续续签会被拒绝，已签发证书的有效期最多为五分钟。账号开通、分组、发布和解绑可在「操作记录」中查看。

接入新线路时，在「节点管理」建立草稿，下载节点程序和接入包。在新 Linux 节点按包内说明安装，将输出的服务器公开 CA 粘贴回后台，再上线并分组。接入包里的帧密钥用于节点服务端。对于配置为兼容监听的节点，旧客户端仍可使用原手动密钥，订阅停用只影响设备证书模式。

## 构建与测试

需要 Java 17+ 和 Maven 3.9+。先将 AuthSystemSimple 的 SDK 安装到本机 Maven 仓库。以下命令从 SimplePlanePlatform 根目录运行，假设两个仓库在同一父目录。

```sh
git clone https://github.com/zhh293/AuthSystemSimple.git ../AuthSystemSimple
git -C ../AuthSystemSimple checkout e1d8fc0
mvn -f ../AuthSystemSimple/pom.xml -pl sso-client-spring-boot-starter -am install
mvn -f subscription-server/pom.xml verify
```

已有认证服务源码时，可直接使用相同版本的本地目录。产物为 `subscription-server/target/subscription-server-1.0.0-SNAPSHOT.jar`。集成测试使用 H2 覆盖配对和撤销、并发设备名额、签名重放以及分组变动对清单的影响。构建节点程序可在项目根目录运行 `mvn -pl proxy-remote -am package`。

## 部署配置

在 AuthSystemSimple 登记机密 OIDC 客户端，授权范围包含 `openid`，精确登记回调 `<订阅服务外部 HTTPS 地址>/sso/callback`。开放 OIDC discovery 与 JWKS，为浏览器和客户端反代订阅服务的 `/sso/`、`/account/`、`/device/` 和 `/admin/`。完整示例见 [Nginx 配置](../ops/subscriptions/https.conf)。

| 配置 | 用途 |
| --- | --- |
| `SP_PUBLIC_ORIGIN`、`SP_BIND_ADDRESS`、`SP_PORT` | 外部 HTTPS 地址、本机监听地址和端口 |
| `SSO_ISSUER`、`SSO_CLIENT_ID`、`SSO_CLIENT_SECRET` | AuthSystemSimple 的 issuer 和已注册的 OIDC 客户端 |
| `SP_DB_URL`、`SP_DB_USER`、`SP_DB_PASSWORD`、`SP_REDIS_URL` | 订阅 MySQL 库和 SDK 会话 Redis |
| `SP_IDENTITY_SCHEMA` | 同实例的 AuthSystemSimple 库名，默认 `sso_authsystem_v2` |
| `SP_ADMIN_SUBJECTS` | 逗号分隔的管理员 OIDC subject，取自同一 issuer |
| `SSO_CLIENT_LOOKUP_HMAC_KEY`、`SSO_CLIENT_REFRESH_AES_KEY`、`SP_CHALLENGE_KEY` | 分别生成并以 Base64 提供的 32 字节密钥 |

先初始化 AuthSystemSimple 身份库和订阅数据库用户，再由数据库管理员应用 [身份库视图和存储过程](../ops/subscriptions/console-identity.sql)。采用自定义库名或数据库账号时，同步更新该 SQL 的限定库名和授权对象。订阅服务启动时用 Flyway 迁移订阅库。启动命令如下。

```sh
java -jar subscription-server/target/subscription-server-1.0.0-SNAPSHOT.jar
```

托管实例的 [Compose 配置](../ops/subscriptions/compose.yaml) 包含订阅、认证、会话存储和验证节点。准备站点自己的环境文件、证书和挂载的 JAR，再按需复用。管理端下载的节点程序来自容器的 `/app/node-runtime.jar`。恢复数据库时需保留原始 `SP_CHALLENGE_KEY`，用于解密节点签发材料。

## 验收和发布状态

远端已验证 OIDC 登录、后台建用户、分组实时下发、停订、解绑和已有 9090 线路兼容。Windows 订阅流程在调试构建中验证，正式桌面安装包仍待发布。Android 真机浏览器、Keystore 和 VPN 联调仍待验收。[运营验收与截图](../docs/sdd/node-subscriptions/operations-console.md) · [协议设计](../docs/sdd/node-subscriptions/design.md) · [客户端验证记录](../docs/sdd/node-subscriptions/verification.md)
