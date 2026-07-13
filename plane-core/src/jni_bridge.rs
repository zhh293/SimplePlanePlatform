use std::any::Any;
use std::net::Ipv4Addr;
use std::panic::{catch_unwind, AssertUnwindSafe};
use std::sync::Arc;

use jni::objects::{GlobalRef, JObject, JString, JValue};
use jni::sys::{jint, jlong, jstring};
use jni::{JNIEnv, JavaVM};

use crate::android_tun::AndroidTun;
use crate::dispatcher::{run_dispatcher, DispatcherConfig, RemoteNodeConfig};
use crate::error::{CoreError, Result};
use crate::mobile_config::AndroidConfig;
use crate::net_probe::FakeDnsEngine;
use crate::outbound::SocketProtector;
use crate::stats::CoreStats;
use crate::tcp_stack::{stack_loop, TcpEvent};

const FAKE_IP_CIDR: &str = "198.18.0.0/15";
const FAKE_DNS_CAPACITY: usize = 4096;
const TCP_EVENT_CHANNEL_CAP: usize = 256;
const NOTIFY_CHANNEL_CAP: usize = 1024;

pub struct CallbackCtx {
    vm: JavaVM,
    bridge: Option<GlobalRef>,
}

impl CallbackCtx {
    pub fn protect(&self, fd: i32) -> Result<bool> {
        let mut env = self.vm.attach_current_thread()?;
        let bridge = self.bridge.as_ref().ok_or_else(|| {
            CoreError::Internal("JNI callback context already disposed".to_string())
        })?;
        let ret = env.call_method(bridge.as_obj(), "protect", "(I)Z", &[JValue::Int(fd)])?;
        Ok(ret.z()?)
    }

    pub fn on_status(&self, state: &str) -> Result<()> {
        let mut env = self.vm.attach_current_thread()?;
        let bridge = self.bridge.as_ref().ok_or_else(|| {
            CoreError::Internal("JNI callback context already disposed".to_string())
        })?;
        let jstr = env.new_string(state)?;
        env.call_method(
            bridge.as_obj(),
            "onStatus",
            "(Ljava/lang/String;)V",
            &[JValue::Object(&jstr)],
        )?;
        Ok(())
    }

    pub fn resolve_ipv4(&self, host: &str) -> Result<Option<Ipv4Addr>> {
        let mut env = self.vm.attach_current_thread()?;
        let bridge = self.bridge.as_ref().ok_or_else(|| {
            CoreError::Internal("JNI callback context already disposed".to_string())
        })?;
        let jhost = env.new_string(host)?;
        let ret = env.call_method(
            bridge.as_obj(),
            "resolveIpv4",
            "(Ljava/lang/String;)Ljava/lang/String;",
            &[JValue::Object(&jhost)],
        )?;
        let obj = ret.l()?;
        if obj.is_null() {
            return Ok(None);
        }
        let ip: String = env.get_string(&JString::from(obj))?.into();
        Ok(ip.parse::<Ipv4Addr>().ok())
    }
}

impl Drop for CallbackCtx {
    fn drop(&mut self) {
        let Some(bridge) = self.bridge.take() else {
            return;
        };
        match self.vm.attach_current_thread() {
            Ok(_guard) => drop(bridge),
            Err(e) => {
                tracing::warn!(error = %e, "failed to attach before dropping JNI callback");
                drop(bridge);
            }
        }
    }
}

struct JniProtector {
    cb: Arc<CallbackCtx>,
}

impl SocketProtector for JniProtector {
    fn protect(&self, fd: i32) -> bool {
        match self.cb.protect(fd) {
            Ok(ok) => ok,
            Err(e) => {
                tracing::warn!(fd, error = %e, "JNI protect callback failed");
                false
            }
        }
    }

    fn resolve_ipv4(&self, host: &str) -> Option<Ipv4Addr> {
        match self.cb.resolve_ipv4(host) {
            Ok(ip) => ip,
            Err(e) => {
                tracing::warn!(host, error = %e, "JNI resolve callback failed");
                None
            }
        }
    }

    fn allow_unprotected_dns_fallback(&self) -> bool {
        false
    }
}

pub struct CoreHandle {
    _cb: Arc<CallbackCtx>,
    rt: tokio::runtime::Runtime,
    shutdown: tokio::sync::watch::Sender<bool>,
    #[allow(dead_code)]
    config: AndroidConfig,
    stats: Arc<CoreStats>,
}

impl Drop for CoreHandle {
    fn drop(&mut self) {
        let _ = self.shutdown.send(true);
        self.stats.set_state("stopped");
        tracing::debug!("CoreHandle dropped, shutdown signalled");
    }
}

pub(crate) fn catch_unwind_to_jlong<F>(f: F) -> jlong
where
    F: FnOnce() -> Result<jlong>,
{
    match catch_unwind(AssertUnwindSafe(f)) {
        Ok(Ok(v)) => v,
        Ok(Err(e)) => {
            tracing::error!(error = %e, "nativeStart failed");
            0
        }
        Err(payload) => {
            tracing::error!(
                panic = %panic_payload_to_string(payload.as_ref()),
                "nativeStart isolated a panic"
            );
            0
        }
    }
}

pub(crate) fn catch_unwind_to_unit<F>(f: F)
where
    F: FnOnce() -> Result<()>,
{
    match catch_unwind(AssertUnwindSafe(f)) {
        Ok(Ok(())) => {}
        Ok(Err(e)) => tracing::error!(error = %e, "JNI call failed"),
        Err(payload) => tracing::error!(
            panic = %panic_payload_to_string(payload.as_ref()),
            "JNI call isolated a panic"
        ),
    }
}

fn panic_payload_to_string(payload: &(dyn Any + Send)) -> String {
    if let Some(message) = payload.downcast_ref::<&'static str>() {
        (*message).to_string()
    } else if let Some(message) = payload.downcast_ref::<String>() {
        message.clone()
    } else {
        "non-string panic payload".to_string()
    }
}

fn native_start_impl(
    env: &mut JNIEnv,
    this: &JObject,
    tun_fd: jint,
    config_json: &JString,
) -> Result<jlong> {
    if tun_fd < 0 {
        return Err(CoreError::InvalidArgument(format!(
            "tunFd must be >= 0, got {tun_fd}"
        )));
    }

    let json: String = env.get_string(config_json)?.into();
    let config = AndroidConfig::from_json(&json)?;
    if !config.outbound_ready() {
        // Kotlin detached the fd before calling nativeStart, so close it on early error.
        unsafe {
            libc::close(tun_fd);
        }
        return Err(CoreError::InvalidArgument(
            "no ready remote node in config".to_string(),
        ));
    }

    let vm = env.get_java_vm()?;
    let bridge = env.new_global_ref(this)?;
    let rt = tokio::runtime::Builder::new_multi_thread()
        .worker_threads(2)
        .enable_all()
        .build()?;
    let (shutdown, _rx) = tokio::sync::watch::channel(false);
    let cb = Arc::new(CallbackCtx {
        vm,
        bridge: Some(bridge),
    });
    let stats = Arc::new(CoreStats::new());

    let handle = Box::new(CoreHandle {
        _cb: Arc::clone(&cb),
        rt,
        shutdown,
        config: config.clone(),
        stats: Arc::clone(&stats),
    });

    {
        // AndroidTun wraps the raw fd in tokio::io::unix::AsyncFd, which must be
        // created while a Tokio reactor is active. nativeStart runs on a Java
        // thread, so enter the freshly-created runtime before wiring the data plane.
        let _runtime_guard = handle.rt.enter();
        spawn_data_plane(&handle, &cb, tun_fd, &config)?;
    }
    stats.set_state("connected");
    let _ = cb.on_status("connected");

    Ok(Box::into_raw(handle) as jlong)
}

fn spawn_data_plane(
    handle: &CoreHandle,
    cb: &Arc<CallbackCtx>,
    tun_fd: jint,
    config: &AndroidConfig,
) -> Result<()> {
    let tun = unsafe { AndroidTun::from_raw_fd(tun_fd, config.mtu) }?;
    let (tun_reader, tun_writer) = tun.split();

    let fake_dns = Arc::new(tokio::sync::Mutex::new(FakeDnsEngine::new(
        FAKE_IP_CIDR,
        FAKE_DNS_CAPACITY,
    )));
    let (tcp_event_tx, tcp_event_rx) =
        tokio::sync::mpsc::channel::<TcpEvent>(TCP_EVENT_CHANNEL_CAP);
    let (notify_tx, notify_rx) = tokio::sync::mpsc::channel::<()>(NOTIFY_CHANNEL_CAP);

    let nodes = config
        .normalized_remotes()
        .into_iter()
        .map(|node| RemoteNodeConfig {
            name: node.name,
            host: node.host,
            port: node.port,
            key: node.key.into_bytes(),
            cipher: node.cipher,
            tls: node.tls,
        })
        .collect();
    let dispatcher_cfg = DispatcherConfig {
        nodes,
        routing: config.normalized_routing(),
        stats: Arc::clone(&handle.stats),
    };
    let protector = Arc::new(JniProtector { cb: Arc::clone(cb) });

    let stack_fake_dns = Arc::clone(&fake_dns);
    let stack_notify_tx = notify_tx.clone();
    let stack_shutdown_rx = handle.shutdown.subscribe();
    let stack_cb = Arc::clone(cb);
    let stack_stats = Arc::clone(&handle.stats);
    handle.rt.spawn(async move {
        let res = stack_loop(
            tun_reader,
            tun_writer,
            stack_fake_dns,
            tcp_event_tx,
            stack_notify_tx,
            notify_rx,
            stack_shutdown_rx,
        )
        .await;
        match res {
            Ok(()) => tracing::info!("tcp stack exited"),
            Err(e) => {
                stack_stats.set_state("error");
                stack_stats.set_error(format!("stack: {e}"));
                tracing::error!(error = %e, "tcp stack exited with error");
                let _ = stack_cb.on_status("error");
            }
        }
    });

    let disp_shutdown_rx = handle.shutdown.subscribe();
    let disp_cb = Arc::clone(cb);
    let disp_stats = Arc::clone(&handle.stats);
    handle.rt.spawn(async move {
        let res = run_dispatcher(
            tcp_event_rx,
            fake_dns,
            dispatcher_cfg,
            protector,
            notify_tx,
            disp_shutdown_rx,
        )
        .await;
        match res {
            Ok(()) => tracing::info!("dispatcher exited"),
            Err(e) => {
                disp_stats.set_state("error");
                disp_stats.set_error(format!("dispatcher: {e}"));
                tracing::error!(error = %e, "dispatcher exited with error");
                let _ = disp_cb.on_status("error");
            }
        }
    });

    tracing::info!("Android data plane started");
    Ok(())
}

#[no_mangle]
pub extern "C" fn Java_com_proxy_android_NativeBridge_nativeStart(
    mut env: JNIEnv,
    this: JObject,
    tun_fd: jint,
    config_json: JString,
) -> jlong {
    crate::logging::init();
    tracing::info!(tun_fd, "nativeStart called");
    catch_unwind_to_jlong(|| native_start_impl(&mut env, &this, tun_fd, &config_json))
}

#[no_mangle]
pub extern "C" fn Java_com_proxy_android_NativeBridge_nativeStop(
    _env: JNIEnv,
    _this: JObject,
    handle: jlong,
) {
    catch_unwind_to_unit(|| {
        if handle == 0 {
            return Ok(());
        }
        let core = unsafe { Box::from_raw(handle as *mut CoreHandle) };
        core.stats.set_state("stopped");
        let _ = core.shutdown.send(true);
        drop(core);
        tracing::info!("nativeStop completed");
        Ok(())
    });
}

#[no_mangle]
pub extern "C" fn Java_com_proxy_android_NativeBridge_nativeStats(
    env: JNIEnv,
    _this: JObject,
    handle: jlong,
) -> jstring {
    let json = match catch_unwind(AssertUnwindSafe(|| {
        if handle == 0 {
            r#"{"running":false,"state":"stopped"}"#.to_string()
        } else {
            let core = unsafe { &*(handle as *const CoreHandle) };
            core.stats.snapshot_json()
        }
    })) {
        Ok(json) => json,
        Err(_) => {
            r#"{"running":false,"state":"error","last_error":"nativeStats panic"}"#.to_string()
        }
    };

    match env.new_string(json) {
        Ok(s) => s.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn catch_unwind_returns_zero_on_panic() {
        let v = catch_unwind_to_jlong(|| panic!("boom"));
        assert_eq!(v, 0);
    }

    #[test]
    fn catch_unwind_returns_zero_on_err() {
        let v = catch_unwind_to_jlong(|| Err(CoreError::InvalidArgument("x".into())));
        assert_eq!(v, 0);
    }

    #[test]
    fn catch_unwind_returns_value_on_ok() {
        let v = catch_unwind_to_jlong(|| Ok(42));
        assert_eq!(v, 42);
    }

    #[test]
    fn catch_unwind_unit_swallows_panic() {
        catch_unwind_to_unit(|| panic!("boom"));
        catch_unwind_to_unit(|| Err(CoreError::InvalidArgument("x".into())));
        catch_unwind_to_unit(|| Ok(()));
    }
}
