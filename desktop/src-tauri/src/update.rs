//! Exclusive update ownership, including other desktop processes for this user.
use std::fs::{File, OpenOptions};
use std::path::Path;
use tokio::sync::{Mutex, MutexGuard};

#[derive(Default)]
pub struct UpdateState(Mutex<()>);

impl UpdateState {
    pub fn begin(&self) -> Result<MutexGuard<'_, ()>, String> {
        self.0
            .try_lock()
            .map_err(|_| "更新正在进行，请等待当前任务完成。".into())
    }

    pub fn is_busy(&self) -> bool {
        self.0.try_lock().is_err()
    }
}

pub fn lock_installation(directory: &Path) -> Result<File, String> {
    std::fs::create_dir_all(directory).map_err(|e| format!("创建更新目录失败：{e}"))?;
    // Keep the file in place: unlinking a locked file permits a second lock inode.
    let file = OpenOptions::new()
        .create(true)
        .truncate(false)
        .read(true)
        .write(true)
        .open(directory.join("update.lock"))
        .map_err(|e| format!("打开更新锁失败：{e}"))?;
    file.try_lock()
        .map_err(|e| format!("另一个更新任务正在运行，或无法取得更新锁：{e}"))?;
    Ok(file)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn duplicate_updates_are_rejected_and_failure_releases_ownership() {
        let state = UpdateState::default();
        let first = state.begin().unwrap();
        assert!(state.is_busy());
        assert!(state.begin().is_err());
        drop(first);
        assert!(state.begin().is_ok());
    }

    #[test]
    fn independent_instances_cannot_download_concurrently() {
        let directory = tempfile::tempdir().unwrap();
        let first = lock_installation(directory.path()).unwrap();
        assert!(lock_installation(directory.path()).is_err());
        drop(first);
        assert!(lock_installation(directory.path()).is_ok());
    }
}
