//! TUN 设备管理模块
//!
//! 负责创建和管理 TUN 虚拟网卡，包括设备配置、路由设置和 Drop Guard 自动恢复。

use tun2::AbstractDevice;

#[cfg(target_os = "windows")]
use std::net::Ipv4Addr;

use crate::config::{BypassConfig, IntranetDnsConfig, TunConfig};
use crate::error::TunError;
use crate::route_guard::RouteGuard;

/// TUN 设备管理器
pub struct TunManager {
    /// tun2 异步设备
    device: tun2::AsyncDevice,
    /// Drop 时恢复路由
    _route_guard: RouteGuard,
    /// MTU 值
    pub mtu: u16,
}

/// TUN 设备读端
pub struct TunReader {
    reader: tokio::io::ReadHalf<tun2::AsyncDevice>,
}

/// TUN 设备写端
pub struct TunWriter {
    writer: tokio::io::WriteHalf<tun2::AsyncDevice>,
}

impl TunManager {
    /// 创建 TUN 设备并配置路由
    pub async fn new(config: &TunConfig, bypass: &BypassConfig, intranet_dns: &IntranetDnsConfig) -> Result<Self, TunError> {
        tracing::info!("Creating TUN device: {}", config.name);

        let mut tun_config = tun2::Configuration::default();
        tun_config
            .address(config.address)
            .netmask(
                config
                    .netmask
                    .parse::<std::net::Ipv4Addr>()
                    .map_err(|e| TunError::TunDevice(format!("invalid netmask: {}", e)))?,
            )
            .mtu(config.mtu)
            .up();

        #[cfg(target_os = "macos")]
        tun_config.platform_config(|p| {
            // macOS utun 内核驱动 **总是** 在每个包前面发送 4 字节协议信息头（AF_INET/AF_INET6），
            // 这不是可选的。设置 packet_information(true) 让 tun2 自动剥离读取时的 PI 头，
            // 并在写入时自动添加 PI 头。
            p.packet_information(true);
        });

        #[cfg(target_os = "windows")]
        {
            // Windows 使用 Wintun 驱动，不存在 macOS 的 4 字节 PI 头，无需 packet_information。
            // 这里设置一个点对点的目标地址（TUN 子网内的对端 IP），
            // 让 tun2/Wintun 建立 point-to-point 链路。
            let dest_ip = Ipv4Addr::new(198, 18, 255, 254);
            tun_config.destination(dest_ip);
        }

        let device = tun2::create_as_async(&tun_config)
            .map_err(|e| TunError::TunDevice(format!("failed to create TUN device: {}", e)))?;

        // 获取系统分配的实际接口名
        let actual_name = device.tun_name().map_err(|e| {
            TunError::TunDevice(format!("failed to get TUN device name: {}", e))
        })?;
        tracing::info!("TUN device created successfully: {}", actual_name);

        // 设置路由（使用实际接口名）
        let route_guard = RouteGuard::setup(config, bypass, intranet_dns, &actual_name).await?;

        Ok(Self {
            device,
            _route_guard: route_guard,
            mtu: config.mtu,
        })
    }

    /// 分离读写端，支持并发使用
    /// 返回 (reader, writer, route_guard)，调用者需持有 route_guard 直到程序退出
    pub fn split(self) -> (TunReader, TunWriter, RouteGuard) {
        let (reader, writer) = tokio::io::split(self.device);
        (TunReader { reader }, TunWriter { writer }, self._route_guard)
    }
}

impl TunReader {
    /// 从 TUN 设备读取一个 IP 包
    pub async fn read(&mut self, buf: &mut [u8]) -> Result<usize, TunError> {
        use tokio::io::AsyncReadExt;
        let n = self.reader.read(buf).await?;
        Ok(n)
    }
}

impl TunWriter {
    /// 向 TUN 设备写入一个 IP 包
    pub async fn write(&mut self, buf: &[u8]) -> Result<usize, TunError> {
        use tokio::io::AsyncWriteExt;
        let n = self.writer.write(buf).await?;
        Ok(n)
    }

    /// 向 TUN 设备写入一个 IP 包并 flush
    pub async fn write_all(&mut self, buf: &[u8]) -> Result<(), TunError> {
        use tokio::io::AsyncWriteExt;
        self.writer.write_all(buf).await?;
        self.writer.flush().await?;
        Ok(())
    }
}
