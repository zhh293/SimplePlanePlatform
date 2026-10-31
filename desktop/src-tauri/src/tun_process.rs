//! Ownership and readiness for the actual adapter, including UAC processes.
use std::io;
use std::path::{Path, PathBuf};
use std::process::{Child, ChildStderr, ChildStdout};

pub enum TunProcess {
    Child(Child),
    #[cfg(windows)]
    Elevated(std::os::windows::io::OwnedHandle),
}

impl TunProcess {
    pub fn id(&self) -> u32 {
        match self {
            Self::Child(child) => child.id(),
            #[cfg(windows)]
            Self::Elevated(handle) => {
                use std::os::windows::io::AsRawHandle;
                unsafe {
                    windows_sys::Win32::System::Threading::GetProcessId(handle.as_raw_handle())
                }
            }
        }
    }

    pub fn try_exit(&mut self) -> io::Result<Option<u32>> {
        match self {
            Self::Child(child) => child
                .try_wait()
                .map(|s| s.map(|s| s.code().unwrap_or(-1) as u32)),
            #[cfg(windows)]
            Self::Elevated(handle) => {
                use std::os::windows::io::AsRawHandle;
                use windows_sys::Win32::{
                    Foundation::{WAIT_OBJECT_0, WAIT_TIMEOUT},
                    System::Threading::{GetExitCodeProcess, WaitForSingleObject},
                };
                match unsafe { WaitForSingleObject(handle.as_raw_handle(), 0) } {
                    WAIT_TIMEOUT => Ok(None),
                    WAIT_OBJECT_0 => {
                        let mut code = 0;
                        if unsafe { GetExitCodeProcess(handle.as_raw_handle(), &mut code) } == 0 {
                            Err(io::Error::last_os_error())
                        } else {
                            Ok(Some(code))
                        }
                    }
                    _ => Err(io::Error::last_os_error()),
                }
            }
        }
    }

    pub fn terminate(&mut self) -> io::Result<()> {
        match self {
            Self::Child(child) => child.kill(),
            #[cfg(windows)]
            Self::Elevated(handle) => {
                use std::os::windows::io::AsRawHandle;
                if unsafe {
                    windows_sys::Win32::System::Threading::TerminateProcess(
                        handle.as_raw_handle(),
                        1,
                    )
                } == 0
                {
                    Err(io::Error::last_os_error())
                } else {
                    Ok(())
                }
            }
        }
    }

    pub fn take_stdout(&mut self) -> Option<ChildStdout> {
        match self {
            Self::Child(child) => child.stdout.take(),
            #[cfg(windows)]
            Self::Elevated(_) => None,
        }
    }
    pub fn take_stderr(&mut self) -> Option<ChildStderr> {
        match self {
            Self::Child(child) => child.stderr.take(),
            #[cfg(windows)]
            Self::Elevated(_) => None,
        }
    }
}

pub struct TunRun {
    pub ready: PathBuf,
    pub stop: PathBuf,
    pub log: PathBuf,
}

impl TunRun {
    pub fn create(base: &Path) -> io::Result<Self> {
        let stamp = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .map_err(io::Error::other)?
            .as_nanos();
        let runtime = base.join("tun-runtime");
        std::fs::create_dir_all(&runtime)?;
        let directory = runtime.join(format!("{}-{}", std::process::id(), stamp));
        std::fs::create_dir(&directory)?;
        Ok(Self {
            ready: directory.join("ready"),
            stop: directory.join("stop"),
            log: base.join("tun-adapter.log"),
        })
    }

    pub fn is_ready(&self, expected_pid: Option<u32>) -> bool {
        let pid = std::fs::read_to_string(&self.ready)
            .ok()
            .and_then(|s| s.trim().parse::<u32>().ok());
        matches!(pid, Some(pid) if pid > 0 && expected_pid.is_none_or(|expected| pid == expected))
    }

    pub fn arguments(&self, config: &Path) -> Vec<std::ffi::OsString> {
        vec![
            "--config".into(),
            config.into(),
            "--ready-file".into(),
            self.ready.clone().into(),
            "--stop-file".into(),
            self.stop.clone().into(),
            "--log-file".into(),
            self.log.clone().into(),
        ]
    }

    pub fn request_stop(&self) -> io::Result<()> {
        std::fs::write(&self.stop, b"stop")
    }
    pub fn cleanup(&self) {
        let _ = std::fs::remove_file(&self.ready);
        let _ = std::fs::remove_file(&self.stop);
        if let Some(dir) = self.ready.parent() {
            let _ = std::fs::remove_dir(dir);
        }
    }
}

/// Windows CRT quoting: paths can contain spaces and trailing backslashes.
#[cfg(windows)]
pub fn quote_argument(arg: &std::ffi::OsStr) -> String {
    let mut result = String::from("\"");
    let mut slashes = 0;
    for ch in arg.to_string_lossy().chars() {
        if ch == '\\' {
            slashes += 1;
            continue;
        }
        if ch == '"' {
            result.extend(std::iter::repeat_n('\\', slashes * 2 + 1));
        } else {
            result.extend(std::iter::repeat_n('\\', slashes));
        }
        slashes = 0;
        result.push(ch);
    }
    result.extend(std::iter::repeat_n('\\', slashes * 2));
    result.push('"');
    result
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn readiness_is_per_run_and_matches_real_pid() {
        let dir = tempfile::tempdir().unwrap();
        let a = TunRun::create(dir.path()).unwrap();
        let b = TunRun::create(dir.path()).unwrap();
        assert!(!a.is_ready(Some(42)));
        std::fs::write(&a.ready, "42").unwrap();
        assert!(a.is_ready(Some(42)));
        assert!(!a.is_ready(Some(43)));
        assert!(!b.is_ready(Some(42)));
        a.request_stop().unwrap();
        assert!(!b.stop.exists());
        a.cleanup();
        b.cleanup();
    }

    #[cfg(windows)]
    #[test]
    fn native_handle_observes_exit_code_259_as_terminated() {
        use std::os::windows::{
            io::{AsRawHandle, FromRawHandle, OwnedHandle},
            process::CommandExt,
        };
        use windows_sys::Win32::{
            Foundation::{DuplicateHandle, DUPLICATE_SAME_ACCESS},
            System::Threading::GetCurrentProcess,
        };
        let mut child = std::process::Command::new("cmd.exe")
            .args(["/D", "/C", "exit 259"])
            .creation_flags(0x08000000)
            .spawn()
            .unwrap();
        let mut raw = std::ptr::null_mut();
        unsafe {
            let current = GetCurrentProcess();
            assert_ne!(
                DuplicateHandle(
                    current,
                    child.as_raw_handle(),
                    current,
                    &mut raw,
                    0,
                    0,
                    DUPLICATE_SAME_ACCESS
                ),
                0
            );
        }
        let mut process = TunProcess::Elevated(unsafe { OwnedHandle::from_raw_handle(raw) });
        assert_eq!(process.id(), child.id());
        child.wait().unwrap();
        assert_eq!(process.try_exit().unwrap(), Some(259));
    }
}
