# Windows TUN 稳定性设计

## 数据面（TS-01/02/03）

- 将 active connection 的推进抽为可测试函数。上传先预留 channel 容量再读 socket；下载保存待写块与偏移，完成后才处理下一条命令。
- TCP 接收 EOF 后释放上传 sender，消费者先读完队列再看到 EOF。下载 Close 与数据同队列，可靠入队；close 后继续保留 socket 处理 FIN/ACK。终态/失败清理统一收集 handle，先 poll 输出 RST/FIN，再从 SocketSet 删除。双向已结束后的关闭阶段设有上限，避免 FIN 无响应永久占用。
- SmolTcpStream 对零长度读写、队列满时的 shutdown、取消后的 reserve 和重复 EOF 提供明确语义。
- 转发使用同一任务中的 copy_bidirectional；以双向成功 I/O 更新共享活动时间，连接级 120 秒无进展才超时。错误/取消直接释放双方，不 spawn 游离的复制任务。半关闭沿用双向复制的语义。

## 桌面控制（TS-04/05/06）

- 统一的 TUN 进程包装支持 std::process::Child 和 Windows OwnedHandle。用 WaitForSingleObject + GetExitCodeProcess 观察真正进程，句柄 RAII 释放；UAC 不创建占位 cmd。
- 每次启动分配独立的 ready/stop 文件路径，通过 CLI 传递；进程完成路由和模块初始化后写入实际 PID。桌面轮询进程与本次 ready 文件，Windows 校验 PID。最多等待 30 秒，未就绪清理并报错。
- stop 文件触发 main 的优雅退出分支，随后 Drop 恢复网络。桌面等待退出；必要时仅终止所持句柄/PID，失败保留 Error 及进程信息，不虚报 Stopped。不自动接管无句柄的外部 Windows TUN。
- status 查询刷新 TUN 状态，重新连接前也刷新。生命周期消息记录实际退出码和日志路径。控制文件按运行实例隔离，避免上次 ready 被复用。
- 桌面页面复用现有 TUN 异常卡片，系统代理开关文案同时检查运行模式；窗口关闭先等待清理，停止失败时保持窗口打开。
- Windows tun2 配置显式设置名称。CLI 支持日志路径覆盖，桌面每次启动启用同一可轮转日志文件；日志追加，单文件约 5 MiB，保留一个备份。核心任务异常返回非零；正常 stop 返回零。

## 测试策略（TS-07）

使用真实 smoltcp 双端内存网络测试字节完整性/FIN/RST/资源回收；使用 Tokio 可控时间和 duplex/localhost SOCKS5 测试单向长传输、空闲、半关闭、错误及取消。桌面进程测试启动无权限的短命进程、校验 ready/PID、退出刷新和停止，Windows 原生进程句柄走相同监测实现。持久日志测试追加/轮转。保留原有 Cargo 测试，做 Windows debug/release 编译。真实 Wintun/UAC 若环境不允许应明确未验收，不能借用内存测试替代。
