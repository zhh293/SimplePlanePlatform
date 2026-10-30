use serde::{Deserialize, Serialize};
use std::process::Child;
use std::sync::Arc;
use tokio::sync::Mutex;

use crate::logs::LogManager;

/// 服务运行状态
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "lowercase")]
pub enum ServiceStatus {
    Stopped,
    Starting,
    Running,
    Stopping,
    Error,
}

/// 代理模式
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "lowercase")]
pub enum ProxyMode {
    System,
    Tun,
}

/// 应用全局状态
pub struct AppState {
    pub proxy_process: Option<Child>,
    pub tun_process: Option<crate::tun_process::TunProcess>,
    pub tun_run: Option<crate::tun_process::TunRun>,
    pub proxy_status: ServiceStatus,
    pub tun_status: ServiceStatus,
    pub proxy_mode: ProxyMode,
    pub proxy_port: u16,
    pub http_port: u16,
    /// 系统代理设置前的原始值（用于还原）
    pub original_proxy_state: Option<OriginalProxyState>,
    /// 实时日志管理器
    pub log_manager: Arc<Mutex<LogManager>>,
    /// 标记 proxy-local 是否为外部进程（端口已被占用时复用）
    pub proxy_external: bool,
}

/// 保存设置前的系统代理状态，用于退出时还原
#[derive(Debug, Clone)]
pub struct OriginalProxyState {
    pub http_enabled: bool,
    pub https_enabled: bool,
    pub socks_enabled: bool,
    pub http_host: String,
    pub http_port: String,
    pub https_host: String,
    pub https_port: String,
    pub socks_host: String,
    pub socks_port: String,
}

impl Default for AppState {
    fn default() -> Self {
        Self {
            proxy_process: None,
            tun_process: None,
            tun_run: None,
            proxy_status: ServiceStatus::Stopped,
            tun_status: ServiceStatus::Stopped,
            proxy_mode: ProxyMode::System,
            proxy_port: 1080,
            http_port: 1080,
            original_proxy_state: None,
            log_manager: Arc::new(Mutex::new(LogManager::new(2000))),
            proxy_external: false,
        }
    }
}

impl AppState {
    /// 退出时清理所有子进程和系统代理设置
    pub async fn cleanup(&mut self) -> Result<(), String> {
        log::info!("Cleaning up: stopping all processes and restoring proxy settings");

        // Keep the application alive if its privileged adapter could not be stopped.
        if self.tun_process.is_some() || self.tun_status != ServiceStatus::Stopped {
            crate::process::stop_tun_adapter(self).await?;
        }

        // 停止代理进程（外部进程不管）
        if !self.proxy_external {
            if let Some(ref mut child) = self.proxy_process {
                let _ = child.kill();
                let _ = child.wait();
            }
        }
        self.proxy_process = None;
        self.proxy_status = ServiceStatus::Stopped;

        // 还原系统代理
        if self.original_proxy_state.is_some() {
            let _ = crate::proxy::restore_system_proxy(self).await;
        }

        // DNS 兜底恢复（等待 tun-adapter 的 Drop handler 完成）
        tokio::time::sleep(std::time::Duration::from_secs(2)).await;
        crate::dns::restore_dns_if_needed();
        Ok(())
    }
}
