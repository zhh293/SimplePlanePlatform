//! Bounded, append-only diagnostics, including hidden elevated Windows launches.
use std::fs::{File, OpenOptions};
use std::io::{self, Write};
use std::path::{Path, PathBuf};
use std::sync::{Arc, Mutex};

#[derive(Clone)]
pub struct RollingLog(Arc<Mutex<LogFile>>);

struct LogFile {
    path: PathBuf,
    file: Option<File>,
    length: u64,
    limit: u64,
}

impl RollingLog {
    pub fn open(path: &Path, limit: u64) -> io::Result<Self> {
        let file = OpenOptions::new().create(true).append(true).open(path)?;
        let length = file.metadata()?.len();
        Ok(Self(Arc::new(Mutex::new(LogFile {
            path: path.into(),
            file: Some(file),
            length,
            limit,
        }))))
    }
}

impl Write for RollingLog {
    fn write(&mut self, bytes: &[u8]) -> io::Result<usize> {
        let mut log = self
            .0
            .lock()
            .map_err(|_| io::Error::other("log lock poisoned"))?;
        if log.length > 0 && log.length.saturating_add(bytes.len() as u64) > log.limit {
            log.file.take(); // Windows requires closing before rename.
            let backup = log.path.with_extension("log.1");
            match std::fs::remove_file(&backup) {
                Ok(()) => {}
                Err(e) if e.kind() == io::ErrorKind::NotFound => {}
                Err(e) => return Err(e),
            }
            std::fs::rename(&log.path, backup)?;
            log.length = 0;
        }
        if log.file.is_none() {
            log.file = Some(
                OpenOptions::new()
                    .create(true)
                    .append(true)
                    .open(&log.path)?,
            );
        }
        let n = log.file.as_mut().unwrap().write(bytes)?;
        log.length += n as u64;
        Ok(n)
    }
    fn flush(&mut self) -> io::Result<()> {
        let mut log = self
            .0
            .lock()
            .map_err(|_| io::Error::other("log lock poisoned"))?;
        if let Some(file) = &mut log.file {
            file.flush()?;
        }
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn append_and_rotate_preserves_previous_run() {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("tun.log");
        RollingLog::open(&path, 12)
            .unwrap()
            .write_all(b"first\n")
            .unwrap();
        let mut log = RollingLog::open(&path, 12).unwrap();
        log.write_all(b"next\n").unwrap();
        assert_eq!(std::fs::read(&path).unwrap(), b"first\nnext\n");
        log.write_all(b"third\n").unwrap();
        assert_eq!(
            std::fs::read(path.with_extension("log.1")).unwrap(),
            b"first\nnext\n"
        );
        assert_eq!(std::fs::read(&path).unwrap(), b"third\n");
        log.write_all(b"fourth\n").unwrap();
        assert_eq!(
            std::fs::read(path.with_extension("log.1")).unwrap(),
            b"third\n"
        );
    }
}
