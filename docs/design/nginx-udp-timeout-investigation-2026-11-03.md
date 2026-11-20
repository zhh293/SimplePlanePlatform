# HTTP/3 节点持续超时排查与 Nginx UDP 缓冲区调优记录

日期：2026-11-03。除标明 UTC 的服务器原始日志外，本文时间均为北京时间（UTC+8）。

## 1. 现象与结论

桌面客户端连接 `20.78.53.111:9090` 后，开始可以正常访问网站，测速可达十几 MB/s，但使用一段时间后会连续出现约 8 秒的请求超时。切换到另一个节点 `54.172.101.190:9090` 后，用户体验明显稳定。

本轮被动监控捕获了用户实际使用过程中的完整故障。证据表明：

1. 20 节点的 Nginx 在向客户端转发 UDP 时触发 `shared connection is busy` 错误。
2. 对应 UDP 转发会话被终止，后续数据通过新建的上游 UDP 源端口转发给 Java。
3. 既有 QUIC 连接未恢复正常的应用请求传输，客户端随即连续出现 8 秒超时。
4. 故障窗口内，系统 UDP 发送缓冲区错误计数增加两次，与两条 Nginx 告警吻合。

因此，本次故障的直接中断点定位在 Nginx UDP 转发层。瞬时发送缓冲区压力是高度可信的触发因素；本轮没有进行系统调用跟踪，不能声称逐次捕获了 `sendmsg()` 返回 EAGAIN，也不能据此认定历史上所有超时都只有同一个原因。

在不升级 Nginx、不改动客户端或 Java 业务代码的情况下，增大该 UDP 监听 socket 的收发缓冲区后，用户反馈稳定性改善；随后观察期内未新增同类 Nginx 告警，UDP 缓冲区错误计数保持不变。观察时间有限，结论是“调整有效且与定位吻合”，不是长期稳定性已经得到充分证明。

## 2. 实际链路

```text
浏览器 / TUN
    ↓ HTTP CONNECT 或 SOCKS5
本地 proxy-local
    ↓ HTTP/3 over QUIC / UDP
20.78.53.111:9090（Nginx stream UDP 监听）
    ↓ UDP 转发
127.0.0.1:19090（Java proxy-remote）
    ↓ TCP
目标网站
```

这条链路包含两个不同的“收到包”：

- 公网口收到 UDP，只说明数据到达服务器。
- Java 应用记录对应 CONNECT，才说明数据已经通过 Nginx、QUIC 和应用解码，到达业务处理层。

返回方向同样需要区分 Java 发往 Nginx 的回环 UDP 与 Nginx 发往客户端的公网 UDP。只看公网入包，无法证明整个代理链路正常。

## 3. 两个节点是否运行相同服务端

核对实际 Java 进程启动参数，两台节点都使用：

```text
/opt/simpleplane/releases/b125737/proxy-remote.jar
```

两台该文件的 SHA256 相同：

```text
5e193acd94d442c2693d81c3a6df12fce9c6b0ab766a99bf955918a0e14de8be
```

主要传输配置也一致：HTTP/3、60 秒 QUIC idle timeout、1000 条双向流上限、16 MiB 初始连接窗口、1 MiB 初始流窗口等。证书路径与节点身份配置不同。

但运行环境并不一致：

| 项目 | 20.78.53.111 | 54.172.101.190 |
|---|---|---|
| 平台 | Azure / Ubuntu 22.04 | AWS / Amazon Linux 2023 |
| 内核 | 6.8.0-1070-azure | 6.18.41-94.142.amzn2023.x86_64 |
| Nginx | 1.18.0，Ubuntu 软件包 1.18.0-6ubuntu14.22 | 1.30.4 |
| JVM | Ubuntu OpenJDK 17 | Corretto OpenJDK 17 |
| 调优前 wmem_max | 212992 字节 | 4194304 字节 |
| 调优前 rmem_max | 1048576 字节 | 4194304 字节 |
| UDP 9090 监听 | `udp reuseport` | `udp` |

不同节点的累计丢包总数不能直接比较：运行时长、服务负载以及其他 UDP 服务不同。排查时应比较同一故障窗口内的计数增量，而不是看到某台总数更大就认定其更差。

## 4. 监控方式与主动测试的边界

最初使用独立本地测试代理固定连接 20 节点，以 8 路并发访问 Google、Bing、example.com 和静态图片，复用同一条 HTTP/3 连接。该轮完成 736 次 HTTPS 请求、读取约 40 MB 正文，没有复现同样的持续超时。这不能推翻用户的实际故障，也不能代表高速下载场景。

随后按用户要求停止主动请求，改为两小时上限的被动监控，让用户自行操作桌面客户端。采集内容包括：

- 本地 proxy-local、TUN 日志与运行状态。
- Java/TUN 进程、DNS 地址及故障时的路由、socket 和线程快照。
- 远端 Nginx error.log 与 Java 服务日志。
- 远端公网 UDP 9090 和回环 UDP 19090 两侧的包头、方向、长度与时间。
- 远端 UDP 错误计数、socket 内存和系统资源。

监控没有主动访问网站，没有切换用户节点，也没有修改 DNS 或路由配置。异常触发的线程快照采用限频方式；远端 Java 使用 SIGQUIT 输出线程信息，不终止服务。

用户在实际使用中复现后，抓包和日志才形成完整关联。原始记录留在本地 `.build/` 下，不随本文提交；其中可能含域名、客户端地址及详细运行信息。本文将客户端公网地址表示为 `CLIENT_IP`。

## 5. 实际故障时间线

| 时间 | 事件 |
|---|---|
| 13:12:14 | 被动监控启动 |
| 13:13:31 | 客户端与 20.78.53.111:9090 完成 HTTP/3 握手 |
| 13:14:53 左右 | Java 仍收到新 CONNECT，请求号推进至 151 |
| 13:14:54 | Nginx 连续出现两条 `shared connection is busy` 告警 |
| 13:14:54 | Nginx 到 Java 的 UDP 上游源端口连续变化，原转发映射中断 |
| 13:15:14.188 | 本地 requestId=152 报 8026ms 超时 |
| 13:15:25—13:15:51 | 本地 requestId=153—183 连续超时 |
| 13:15:54 左右 | 远端大量旧 QUIC 流关闭，约为故障后 60 秒 |
| 13:16:06 | 用户重启/切换后，客户端与另一个节点完成握手 |
| 13:23:57 | Nginx 缓冲区调优生效 |
| 13:36:56 | 按用户要求停止本地被动监控 |

约一分钟的旧流清理与配置的 QUIC idle timeout 一致，但仅凭这个时间关系不能进一步断言所有 native QUIC 内部状态。它说明为什么底层异常后，界面仍可能短时间显示连接可用，而新请求持续失败。

## 6. 关键证据

### 6.1 Nginx 错误

服务器日志使用 UTC，以下 `05:14:54` 对应北京时间 `13:14:54`：

```text
2026/11/03 05:14:54 [alert] ... *5333 shared connection is busy
while proxying and sending to client, udp client: CLIENT_IP,
server: 0.0.0.0:9090, upstream: "127.0.0.1:19090"

2026/11/03 05:14:54 [alert] ... *5335 shared connection is busy
while proxying and sending to client, udp client: CLIENT_IP,
server: 0.0.0.0:9090, upstream: "127.0.0.1:19090"
```

两条告警明确指向向客户端发送 UDP 的环节。

### 6.2 内核 UDP 错误增量

| UTC 采样时间 | UdpSndbufErrors | UdpRcvbufErrors |
|---|---:|---:|
| 05:14:51 | 216 | 42 |
| 05:15:23 | 218 | 42 |

发送缓冲区错误增加 2 次，接收缓冲区错误不变。这是整机计数，只能通过采样把增长定位到该窗口；结合 Nginx 两条告警后，才构成对发送缓冲区压力的有力支持。

### 6.3 UDP 转发端点变化

公网客户端始终使用同一个源端口 65499，而 Nginx 到 Java 的回环源端口发生变化：

```text
故障前：CLIENT_IP:65499 → server:9090 → 127.0.0.1:56657 → Java:19090
故障后：CLIENT_IP:65499 → server:9090 → 127.0.0.1:43241 → Java:19090
随后：  CLIENT_IP:65499 → server:9090 → 127.0.0.1:59759 → Java:19090
```

抓包时间均为 UTC：

- `05:14:54.108456`：旧上游端口 56657 最后一次向 Java 转发客户端数据。
- `05:14:54.112595`：新端口 43241 开始转发。
- `05:14:54.113849`：再次换成端口 59759。
- Java 发往旧端口 56657 的数据延续到 `05:14:55.137801`。
- `05:14:55` 这一秒记录到 26 个后端发包，但公网向客户端发包为 0。

单秒计数会受边界时刻和在途数据影响，不能用任意两侧计数差直接计算丢包率。这里更关键的是：相同公网客户端对应的上游端口在告警时被更换，而 Java 仍向原端口发送数据。

之后，公网客户端的 UDP 仍能到达服务器，并经新端口转交 Java；但是应用日志中最后收到的 CONNECT 为 requestId=151，未出现客户端后来超时的 requestId=152 CONNECT。故障位置因此被缩小到转发会话变化后的 QUIC/应用传输阶段，而非目标网站连接慢。

### 6.4 本地现场

故障期间 Java PID 23040、TUN PID 23564 保持不变；WLAN 和 TUN 的 DNS 地址也保持为 198.18.0.2。用户停止客户端后才出现 DNS 恢复和进程变化。

这次捕获的直接中断点有明确的服务器侧证据，不能继续仅以 DNS 配置解释。此前存在过 DNS 恢复模式问题，属于另一项问题，不应与本次故障合并为同一原因。

## 7. 为什么不是先升级 Nginx

核对 Nginx 1.18.0 官方源码后的处理路径如下：

1. `ngx_udp_sendmsg_chain.c` 中，`sendmsg()` 返回 EAGAIN 时返回 NGX_AGAIN，未发送完的数据链被保留。
2. `ngx_stream_write_filter_module.c` 中，如果数据链尚有剩余且连接是 shared，记录 `shared connection is busy` 并返回 NGX_ERROR。
3. `ngx_stream_proxy_module.c` 中，顶层过滤器返回 NGX_ERROR 后调用 `ngx_stream_proxy_finalize()`，结束代理会话。

因此，这条 alert 不是可以忽略的普通日志，而是会话终止路径的一部分。

进一步对比 1.30.4 和当时 master 的 stream write filter，仍存在相同的 shared connection 错误分支。所以，单纯升级版本并不能保证消除本次触发机制。Ubuntu 包也带有发行版修补，不能仅凭上游版本号旧就断言没有维护。

此外，该节点的 Nginx 同时承载 `/music/callback/` 和 `/music/health` HTTPS 服务，并加载多个发行版动态模块。整体替换软件包会增加其他服务的兼容性变量。本轮采用针对性参数调整，保留版本不变，以便评估调整效果。

## 8. 高吞吐与稳定性为什么可以同时出现差异

用户测速可达到十几 MB/s，说明节点有较好的吞吐潜力，但并不保证每个时刻都能及时把数据排入网卡。

原监听 socket 的发送缓冲上限为 212992 字节，约 208 KiB。以 15 MB/s 做纯量级对比，约相当于十几毫秒的数据量。这个估算忽略了内核内存记账、包开销、分段/聚合和真实调度节奏，不能当作队列时延的精确测量。

如果短时突发、调度延迟或下层发送暂时受阻，平均带宽很高仍可能发生瞬时发送压力。发送缓冲增大可以吸收这种突发；如果长期输入速率超过输出速率，则只能延后缓冲耗尽，不能解决持续拥塞。因此，调优后必须查看队列是否能够及时回落。

## 9. 实施的调优

### 9.1 Nginx 监听参数

文件：`/etc/nginx/stream-conf.d/simpleplane.conf`。

调整前：

```nginx
server {
    listen 9090 udp reuseport;
    proxy_pass 127.0.0.1:19090;
    proxy_connect_timeout 5s;
    proxy_timeout 600s;
    proxy_buffer_size 64k;
}
```

调整后：

```nginx
server {
    listen 9090 udp reuseport sndbuf=4m rcvbuf=2m;
    proxy_pass 127.0.0.1:19090;
    proxy_connect_timeout 5s;
    proxy_timeout 600s;
    proxy_buffer_size 64k;
}
```

`proxy_buffer_size` 与监听 socket 的 SO_SNDBUF/SO_RCVBUF 不是同一个缓冲区。本次调整的是后者，原有 64k 参数保持不变。

### 9.2 内核上限与持久化

新增 `/etc/sysctl.d/99-simpleplane-udp-buffers.conf`：

```conf
net.core.wmem_max = 4194304
net.core.rmem_max = 2097152
```

提高上限，使 Nginx 能申请相应 socket 缓冲区。系统默认值保持原样：

```text
net.core.wmem_default = 212992
net.core.rmem_default = 1048576
```

没有全局提高每个新 socket 的默认缓冲，也没有修改 Java 默认值。

### 9.3 生效与备份

实施顺序为：备份原配置和原内核值、写入持久配置、应用参数、修改监听参数、执行 `nginx -t`、通过后平滑重载。执行脚本包含失败回滚。

远端备份目录：

```text
/var/backups/simpleplane/nginx-udp-20261103-052357
```

`ss -ulnmp` 确认两个 UDP 9090 监听 socket 均已生效：

```text
skmem:(r0,rb4194304,t0,tb8388608,f4096,w0,o0,bl0,d0)
```

Linux 对 SO_RCVBUF/SO_SNDBUF 采用加倍记账，故配置接收 2 MiB、发送 4 MiB，在这里分别显示为 rb=4 MiB、tb=8 MiB。这里的容量上限不等于已经实际占满的内存。

## 10. 调整后的验证结果

本节记录的是现场调优时已完成的检查；整理和提交本文时没有重新运行构建或测试。

- `nginx -t` 通过，Nginx 与 simpleplane-remote 均为 active。
- Java PID 104297 保持不变，没有通过重启 Java 来恢复连接。
- 通过安装版 Java/JAR 启动独立临时本地代理，经 20 节点访问 example.com，证书校验通过，HTTPS 返回 200，正文 577 字节；请求结束后关闭临时代理。
- 同机既有 HTTPS `/music/health` 返回 200。
- 13:29 检查时，调优后本地监控记录到 129 次成功建连、0 次请求超时告警。
- 13:36:58 最终检查时，UdpSndbufErrors 仍为 218、UdpRcvbufErrors 仍为 42，未增加；Nginx 最后两条同类 alert 仍为调优前的 13:14:54。
- 用户反馈实际使用稳定性明显改善。

这些结果支持本次调整，但不能等同于所有业务请求均无错误。浏览器主动关闭、目标站点失败等仍可能产生单条 ClosedChannelException 或 CONNECT 失败，应结合完整链路判断，不能一概视为本次故障复发。

## 11. 如何判断大缓冲有没有慢慢堆积

### 11.1 观察命令

```sh
sudo ss -uanmp
nstat -az | grep -E 'Udp(InDatagrams|OutDatagrams|SndbufErrors|RcvbufErrors)'
tc -s qdisc show dev eth0
```

也可以限制到本链路的 socket：

```sh
sudo ss -HuanmO '( sport = :9090 or sport = :19090 or dport = :19090 )'
```

重点字段：

| 字段 | 含义与用途 |
|---|---|
| Recv-Q | 接收队列占用，观察应用是否及时读取 |
| Send-Q | 发送队列占用，观察是否持续增长 |
| skmem r / t / w | 接收、发送相关内存的实际占用；具体含义见 ss 手册 |
| skmem rb / tb | 收发缓冲区容量上限，不能作为实际积压量 |
| UdpSndbufErrors / UdpRcvbufErrors | 比较观察窗口内的增量 |
| qdisc backlog / drops | 网卡发送调度层是否积压或丢包 |

若队列仅短暂上升后回到低位，通常是突发被吸收；若持续高位、不断增长，或流量降低后仍不回落，就需要继续检查长期速率不匹配、应用读取、CPU 调度、网络出口及 QUIC 层的排队。

### 11.2 本次连续采样

调优后在 13:34—13:35 期间被动采样 60 秒，每秒读取一次相关 socket、UDP 计数和网卡统计：

| 指标 | 结果 |
|---|---|
| 时间点 | 60 个 |
| socket 观测值 | 271 个，socket 数量会随会话结束变化 |
| 非零队列观测 | 1 次 |
| 最大发送队列 | 0 |
| 最大接收队列 | 18368 字节，约 18 KiB |
| 接收峰值位置 | Java UDP 19090 socket |
| 下一秒该队列 | 归零 |
| UDP 发送/接收缓冲区错误增量 | 均为 0 |
| 网卡 TX/RX errors、dropped 增量 | 均为 0 |
| 采样结束 qdisc backlog | 0 字节 / 0 包 |

该分钟网卡发送 17784767 字节，接收 12840430 字节，属于整机网卡统计，不是单一代理业务的精确流量。

采样期间没有持续积压证据。但一秒粒度可能漏掉毫秒级突发，而且这一分钟没有达到用户测速时十几 MB/s 的持续吞吐，不能据此声称满速时也绝无排队。socket 队列归零也不等于 QUIC native 或 Java 用户态内部完全没有待处理数据。

## 12. 停止监控与保留现场

按用户要求，13:36:56 本地监控写入结束状态并退出。随后检查远端，没有发现本轮 tcpdump、日志跟随或临时测试服务仍在运行。生产 Nginx、Java、桌面客户端和 TUN 不属于被停止的监控任务。

日志证据保留于本地：

```text
.build/tun-passive/20261103-131214/
.build/node-b-diagnosis/soak-20261103-130522/
.build/node-b-diagnosis/nginx-tuning-result.log
.build/node-b-diagnosis/queues-20261103-133410.jsonl
.build/node-b-diagnosis/queues-20261103-133410.summary.json
```

其中 `remote-nginx.log`、`remote-packets.log`、`remote-state.log`、`remote-java.log`、`proxy-local.log`、`state.log` 与 `snapshot-131453/` 分别支持告警、双侧包、计数增量、应用请求和本地现场的核对。

## 13. 回滚方式

远端备份目录包含原监听配置、原内核上限、持久配置文件原始状态和可执行回滚脚本：

```sh
sudo /var/backups/simpleplane/nginx-udp-20261103-052357/rollback.sh
```

脚本恢复原配置及内核上限，通过 `nginx -t` 后平滑重载。回滚会恢复原来的较小缓冲区，可能重新暴露此次触发条件，应结合实际需要执行。

## 14. 结论的适用范围与后续方向

本轮可确认的事项是：Nginx UDP 转发会话在实际故障时发生中断，发送缓冲区错误同步增加；针对性增大监听缓冲后，现场观察结果改善。

仍需保留边界：

- 不以“换节点就正常”直接排除客户端或 QUIC 的所有恢复能力问题。
- 不把调优后十几分钟的结果当作长期稳定性证明。
- 不把 Nginx 的 native HTTP/3 修复项直接套用于本架构；这里 Nginx 负责 stream UDP 转发，HTTP/3 终止点在 Java。
- 若再次出现同类问题，优先记录缓冲区错误增量、转发源端口变化和双侧包；必要时做绕过 Nginx 的对照测试。
- 如果出现持续积压，应定位实际速率和处理瓶颈，不继续无上限扩大缓冲。

## 15. 参考资料

- [Nginx 1.18.0 UDP 发送实现](https://github.com/nginx/nginx/blob/release-1.18.0/src/os/unix/ngx_udp_sendmsg_chain.c)
- [Nginx 1.18.0 stream write filter](https://github.com/nginx/nginx/blob/release-1.18.0/src/stream/ngx_stream_write_filter_module.c)
- [Nginx 1.18.0 stream proxy 实现](https://github.com/nginx/nginx/blob/release-1.18.0/src/stream/ngx_stream_proxy_module.c)
- [Nginx 1.30.4 stream write filter](https://github.com/nginx/nginx/blob/release-1.30.4/src/stream/ngx_stream_write_filter_module.c)
- [Nginx stream listen 参数：sndbuf、rcvbuf、reuseport](https://nginx.org/en/docs/stream/ngx_stream_core_module.html#listen)
- [Linux ss 手册：队列与 socket 内存字段](https://man7.org/linux/man-pages/man8/ss.8.html)
