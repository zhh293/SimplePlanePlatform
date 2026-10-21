use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::Mutex;

use serde_json::json;

#[derive(Debug, Default)]
pub struct CoreStats {
    upload_bytes: AtomicU64,
    download_bytes: AtomicU64,
    active_connections: AtomicU64,
    total_connections: AtomicU64,
    proxy_connections: AtomicU64,
    direct_connections: AtomicU64,
    rejected_connections: AtomicU64,
    failed_connections: AtomicU64,
    active_node: Mutex<String>,
    state: Mutex<String>,
    last_error: Mutex<String>,
}

impl CoreStats {
    pub fn new() -> Self {
        Self {
            state: Mutex::new("starting".to_string()),
            ..Self::default()
        }
    }

    pub fn add_upload(&self, bytes: usize) {
        self.upload_bytes.fetch_add(bytes as u64, Ordering::Relaxed);
    }

    pub fn add_download(&self, bytes: usize) {
        self.download_bytes
            .fetch_add(bytes as u64, Ordering::Relaxed);
    }

    pub fn begin_connection(&self) {
        self.active_connections.fetch_add(1, Ordering::Relaxed);
        self.total_connections.fetch_add(1, Ordering::Relaxed);
    }

    pub fn end_connection(&self) {
        // Keep the saturating update compatible with older Android build
        // toolchains and newer Rust versions that deprecate fetch_update.
        let mut current = self.active_connections.load(Ordering::Relaxed);
        while current > 0 {
            match self.active_connections.compare_exchange_weak(
                current,
                current - 1,
                Ordering::Relaxed,
                Ordering::Relaxed,
            ) {
                Ok(_) => return,
                Err(observed) => current = observed,
            }
        }
    }

    pub fn inc_proxy(&self) {
        self.proxy_connections.fetch_add(1, Ordering::Relaxed);
    }

    pub fn inc_direct(&self) {
        self.direct_connections.fetch_add(1, Ordering::Relaxed);
    }

    pub fn inc_rejected(&self) {
        self.rejected_connections.fetch_add(1, Ordering::Relaxed);
        self.total_connections.fetch_add(1, Ordering::Relaxed);
    }

    pub fn inc_failed(&self) {
        self.failed_connections.fetch_add(1, Ordering::Relaxed);
    }

    pub fn set_active_node(&self, node: impl Into<String>) {
        if let Ok(mut guard) = self.active_node.lock() {
            *guard = node.into();
        }
    }

    pub fn set_state(&self, state: impl Into<String>) {
        if let Ok(mut guard) = self.state.lock() {
            *guard = state.into();
        }
    }

    pub fn set_error(&self, error: impl Into<String>) {
        if let Ok(mut guard) = self.last_error.lock() {
            *guard = error.into();
        }
    }

    pub fn snapshot_json(&self) -> String {
        let active_node = self
            .active_node
            .lock()
            .map(|v| v.clone())
            .unwrap_or_default();
        let state = self
            .state
            .lock()
            .map(|v| v.clone())
            .unwrap_or_else(|_| "unknown".to_string());
        let last_error = self
            .last_error
            .lock()
            .map(|v| v.clone())
            .unwrap_or_default();

        json!({
            "running": state != "stopped",
            "state": state,
            "active_node": active_node,
            "upload_bytes": self.upload_bytes.load(Ordering::Relaxed),
            "download_bytes": self.download_bytes.load(Ordering::Relaxed),
            "active_connections": self.active_connections.load(Ordering::Relaxed),
            "total_connections": self.total_connections.load(Ordering::Relaxed),
            "proxy_connections": self.proxy_connections.load(Ordering::Relaxed),
            "direct_connections": self.direct_connections.load(Ordering::Relaxed),
            "rejected_connections": self.rejected_connections.load(Ordering::Relaxed),
            "failed_connections": self.failed_connections.load(Ordering::Relaxed),
            "last_error": last_error,
        })
        .to_string()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::Barrier;

    #[test]
    fn end_connection_saturates_at_zero() {
        let stats = CoreStats::new();
        stats.end_connection();
        stats.begin_connection();
        stats.end_connection();
        stats.end_connection();
        let snapshot: serde_json::Value = serde_json::from_str(&stats.snapshot_json()).unwrap();
        assert_eq!(snapshot["active_connections"], 0);
        assert_eq!(snapshot["total_connections"], 1);
    }

    #[test]
    fn concurrent_connection_updates_preserve_counts() {
        let stats = CoreStats::new();
        let barrier = Barrier::new(8);
        stats.begin_connection();
        std::thread::scope(|scope| {
            for _ in 0..8 {
                scope.spawn(|| {
                    barrier.wait();
                    for _ in 0..1000 {
                        stats.begin_connection();
                        stats.end_connection();
                    }
                });
            }
        });
        let snapshot: serde_json::Value = serde_json::from_str(&stats.snapshot_json()).unwrap();
        assert_eq!(snapshot["active_connections"], 1);
        assert_eq!(snapshot["total_connections"], 8001);
        stats.end_connection();
        let snapshot: serde_json::Value = serde_json::from_str(&stats.snapshot_json()).unwrap();
        assert_eq!(snapshot["active_connections"], 0);
    }
}
