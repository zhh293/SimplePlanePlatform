use crate::config::{self, Http3Config, RemoteConfig, RemoteNode};
use base64::{engine::general_purpose::STANDARD, Engine};
use keyring::Entry;
use p256::{
    ecdsa::{signature::Signer, Signature, SigningKey},
    elliptic_curve::rand_core::{OsRng, RngCore},
    pkcs8::{DecodePrivateKey, EncodePrivateKey, EncodePublicKey},
};
use ring::{aead, digest};
use serde::{de::DeserializeOwned, Deserialize, Serialize};
use serde_json::json;
use std::collections::{BTreeMap, HashSet};
use std::time::{Duration, SystemTime, UNIX_EPOCH};
use tokio::sync::Mutex;

const ORIGIN: &str = "https://212.64.14.155";
const ISSUER_CA: &[u8] = include_bytes!("subscription-web-ca.crt");
type Result<T> = std::result::Result<T, String>;

#[derive(Serialize, Deserialize)]
struct Identity {
    private: String,
    device_id: String,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct Pending {
    user_code: String,
    device_secret: String,
    expires_at: u64,
    verification_uri: String,
}

#[derive(Default)]
pub struct SubscriptionState(Mutex<Option<Pending>>);

#[derive(Serialize)]
pub struct PairView {
    code: String,
    url: String,
    expiry: u64,
    qr: Vec<bool>,
    width: usize,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct Poll { status: String, device_id: Option<String> }

#[derive(Deserialize)]
struct Challenge { challenge: String }

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct Manifest { version: u32, expires_at: u64, nodes: Vec<IssuedNode> }

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct IssuedNode {
    id: String, name: String, host: String, port: u16, transport: String,
    server_name: String, ca_pem: String, device_certificate_pem: String, cipher: String, key: String,
}

fn now() -> u64 { SystemTime::now().duration_since(UNIX_EPOCH).unwrap_or_default().as_secs() }
fn entry(name: &str) -> Result<Entry> {
    #[cfg(debug_assertions)]
    if let Some(profile) = config::subscription_test_profile() {
        return Entry::new(&format!("SimplePlane subscriptions {profile}"), name)
            .map_err(|_| "无法打开测试凭据库".into());
    }
    Entry::new("SimplePlane subscriptions", name).map_err(|_| "无法打开系统凭据库".into())
}
fn read_secret(name: &str) -> Result<Option<String>> {
    match entry(name)?.get_password() {
        Ok(value) => Ok(Some(value)),
        Err(keyring::Error::NoEntry) => Ok(None),
        Err(_) => Err("无法读取系统凭据库".into()),
    }
}
fn store_secret(name: &str, value: &str) -> Result<()> {
    entry(name)?.set_password(value).map_err(|_| "无法保存系统凭据".into())
}
fn identity() -> Result<Option<Identity>> {
    read_secret("device")?.map(|s| serde_json::from_str(&s).map_err(|_| "设备凭据损坏".into())).transpose()
}
fn save_identity(value: &Identity) -> Result<()> {
    store_secret("device", &serde_json::to_string(value).map_err(|e| e.to_string())?)
}
fn signing_key(id: &Identity) -> Result<SigningKey> {
    let der = STANDARD.decode(&id.private).map_err(|_| "设备凭据损坏")?;
    SigningKey::from_pkcs8_der(&der).map_err(|_| "设备私钥不可用".into())
}
fn spki(key: &SigningKey) -> Result<String> {
    Ok(STANDARD.encode(key.verifying_key().to_public_key_der().map_err(|_| "公钥编码失败")?.as_bytes()))
}
fn signature(key: &SigningKey, message: &str) -> String {
    let signed: Signature = key.sign(message.as_bytes());
    STANDARD.encode(signed.to_der().as_bytes())
}

fn wrapping_key() -> Result<String> {
    if let Some(key) = read_secret("lease-key")? { return Ok(key); }
    let mut bytes = [0u8; 32];
    OsRng.fill_bytes(&mut bytes);
    let encoded = STANDARD.encode(bytes);
    store_secret("lease-key", &encoded)?;
    Ok(encoded)
}

pub fn configure_java(cmd: &mut std::process::Command) -> Result<()> {
    let dir = config::get_config_dir().join("subscription");
    let key = if dir.join("leases.properties").is_file() {
        Some(read_secret("lease-key")?.ok_or("订阅凭据需要重新同步")?)
    } else {
        wrapping_key().ok()
    };
    if let Some(key) = key {
        cmd.env("SIMPLEPLANE_LEASE_DIR", dir).env("SIMPLEPLANE_LEASE_KEY", key);
    }
    Ok(())
}

async fn post<T: DeserializeOwned>(path: &str, body: serde_json::Value) -> Result<T> {
    let ca = ISSUER_CA;
    #[cfg(debug_assertions)]
    let ca = option_env!("SP_SUBSCRIPTION_CA_PEM").map(str::as_bytes).unwrap_or(ca);
    let root = reqwest::Certificate::from_pem(ca).map_err(|_| "订阅入口 CA 无效")?;
    let builder = reqwest::Client::builder()
        .no_proxy()
        .timeout(Duration::from_secs(12))
        .redirect(reqwest::redirect::Policy::none())
        .tls_built_in_root_certs(false)
        .add_root_certificate(root);
    let mut response = builder.build().map_err(|_| "无法初始化 HTTPS")?
        .post(format!("{ORIGIN}/device/{path}")).json(&body).send().await.map_err(|_| "无法连接订阅服务或证书校验失败")?;
    if !response.status().is_success() { return Err(format!("订阅服务返回 HTTP {}", response.status().as_u16())); }
    let mut bytes = Vec::new();
    while let Some(chunk) = response.chunk().await.map_err(|_| "接收订阅响应失败")? {
        if bytes.len() + chunk.len() > 2 * 1024 * 1024 { return Err("订阅响应过大".into()); }
        bytes.extend_from_slice(&chunk);
    }
    serde_json::from_slice(&bytes).map_err(|_| "订阅服务响应格式错误".into())
}

#[tauri::command]
pub async fn subscription_start(state: tauri::State<'_, SubscriptionState>) -> Result<PairView> {
    let mut pending = state.0.lock().await;
    let id = match identity()? {
        Some(value) if !value.device_id.is_empty() => return Err("请先退出当前订阅账号".into()),
        Some(value) => value,
        None => {
            let key = SigningKey::random(&mut OsRng);
            let value = Identity { private: STANDARD.encode(key.to_pkcs8_der().map_err(|_| "密钥生成失败")?.as_bytes()), device_id: String::new() };
            save_identity(&value)?;
            value
        }
    };
    let name = std::env::var("COMPUTERNAME").or_else(|_| std::env::var("HOSTNAME")).unwrap_or_else(|_| "SimplePlane Desktop".into());
    let request: Pending = post("pair", json!({"name":name,"publicKey":spki(&signing_key(&id)?)?})).await?;
    let expected = format!("{ORIGIN}/account/pair/{}", request.user_code);
    if request.verification_uri != expected || request.user_code.len() != 12
        || !request.user_code.bytes().all(|c| c.is_ascii_uppercase() || c.is_ascii_digit())
        || request.expires_at <= now() { return Err("设备确认地址无效".into()); }
    let qr = qrcode::QrCode::new(expected.as_bytes()).map_err(|_| "二维码生成失败")?;
    let view = PairView { code: request.user_code.clone(), url: expected, expiry: request.expires_at,
        qr: qr.to_colors().iter().map(|c| *c == qrcode::Color::Dark).collect(), width: qr.width() };
    *pending = Some(request);
    Ok(view)
}

#[tauri::command]
pub async fn subscription_poll(state: tauri::State<'_, SubscriptionState>) -> Result<String> {
    let mut pending = state.0.lock().await;
    let request = pending.as_ref().ok_or("请重新开始登录")?;
    if request.expires_at <= now() { *pending = None; return Ok("expired".into()); }
    let mut id = identity()?.ok_or("设备凭据不存在")?;
    let result: Poll = post("poll", json!({"deviceSecret":request.device_secret,
        "signature":signature(&signing_key(&id)?, &format!("simpleplane-pair-v1\n{}", request.device_secret))})).await?;
    if result.status == "approved" {
        let value = result.device_id.ok_or("缺少设备 ID")?;
        if value.len()!=36 || !value.bytes().all(|b| b.is_ascii_hexdigit() || b==b'-') { return Err("无效设备 ID".into()); }
        id.device_id = value;
        save_identity(&id)?;
    }
    if result.status != "pending" { *pending = None; }
    Ok(result.status)
}

fn encode_lease(node: &IssuedNode, tls: &[u8], wrapping: &aead::LessSafeKey) -> Result<String> {
    let cert = node.device_certificate_pem.trim().strip_prefix("-----BEGIN CERTIFICATE-----")
        .and_then(|v| v.strip_suffix("-----END CERTIFICATE-----")).ok_or("设备证书格式错误")?;
    let cert = STANDARD.decode(cert.split_whitespace().collect::<String>()).map_err(|_| "设备证书编码错误")?;
    let mut buffer = Vec::with_capacity(2 + tls.len() + cert.len() + 16);
    buffer.extend_from_slice(&(tls.len() as u16).to_be_bytes());
    buffer.extend_from_slice(tls);
    buffer.extend_from_slice(&cert);
    let mut nonce = [0u8; 12];
    OsRng.fill_bytes(&mut nonce);
    wrapping.seal_in_place_append_tag(aead::Nonce::assume_unique_for_key(nonce),
        aead::Aad::from(node.id.as_bytes()), &mut buffer).map_err(|_| "设备凭据加密失败")?;
    Ok(STANDARD.encode([nonce.as_slice(), &buffer].concat()))
}

fn replacement_selection(nodes: &[RemoteNode], id: &str) -> Option<String> {
    let manual = nodes.iter().find(|node| {
        node.id == id && node.remote.http3.device_credential_id.is_empty()
    })?;
    nodes.iter().find(|node| {
        !node.remote.http3.device_credential_id.is_empty()
            && node.remote.host.eq_ignore_ascii_case(&manual.remote.host)
            && node.remote.port == manual.remote.port
            && node.remote.transport == manual.remote.transport
    }).map(|node| node.id.clone())
}

fn install(manifest: Manifest, tls: &[u8], tun_running: bool) -> Result<usize> {
    if manifest.version != 1 || manifest.expires_at <= now() || manifest.nodes.len() > 128 { return Err("节点清单无效或已过期".into()); }
    let dir = config::get_config_dir().join("subscription");
    std::fs::create_dir_all(&dir).map_err(|e| e.to_string())?;
    let wrap = STANDARD.decode(wrapping_key()?).map_err(|_| "系统凭据损坏")?;
    let key = aead::LessSafeKey::new(aead::UnboundKey::new(&aead::AES_256_GCM, &wrap).map_err(|_| "系统凭据损坏")?);
    let mut cfg = config::load_proxy_config()?;
    let hosts = |cfg: &config::ProxyConfig| cfg.nodes.iter().map(|n| n.remote.host.clone()).collect::<HashSet<_>>();
    let previous_hosts = hosts(&cfg);
    cfg.nodes.retain(|node| node.remote.http3.device_credential_id.is_empty());
    let mut known: HashSet<String> = cfg.nodes.iter().map(|n| n.id.clone()).collect();
    let mut leases = BTreeMap::new();
    for node in &manifest.nodes {
        let id = format!("subscription-{}", node.id);
        if !known.insert(id.clone()) || node.id.is_empty() || node.id.len()>96
            || !node.id.bytes().all(|b| b.is_ascii_alphanumeric() || b==b'_' || b==b'-')
            || node.transport!="http3" || node.host.is_empty() || node.port==0 || node.server_name.is_empty()
            || node.cipher!="aes-gcm-v2" || node.key.len()!=32 {
            return Err("节点清单存在无效节点或 ID 冲突".into());
        }
        let fingerprint = digest::digest(&digest::SHA256,node.ca_pem.as_bytes()).as_ref().iter().map(|b| format!("{b:02x}")).collect::<String>();
        let ca = dir.join(format!("ca-{fingerprint}.pem"));
        reqwest::Certificate::from_pem(node.ca_pem.as_bytes()).map_err(|_| "节点 CA 无效")?;
        if !ca.is_file() { config::atomic_write(&ca, &node.ca_pem)?; }
        leases.insert(node.id.clone(), encode_lease(node,tls,&key)?);
        cfg.nodes.push(RemoteNode { id, name: node.name.clone(), remote: RemoteConfig {
            host: node.host.clone(), port: node.port, cipher:node.cipher.clone(), key:node.key.clone(), transport:"http3".into(),
            http3: Http3Config {device_credential_id:node.id.clone(),server_name:node.server_name.clone(),
                ca_file:ca.to_string_lossy().into_owned(), ..Default::default()} } });
    }
    if let Some(first) = cfg.nodes.first() {
        if let Some(id) = replacement_selection(&cfg.nodes, &cfg.selected_node_id) {
            cfg.selected_node_id = id;
        } else if !known.contains(&cfg.selected_node_id) {
            cfg.selected_node_id = first.id.clone();
        }
        if let Some(id) = replacement_selection(&cfg.nodes, &cfg.preferred_node_id) {
            cfg.preferred_node_id = id;
        } else if !known.contains(&cfg.preferred_node_id) {
            cfg.preferred_node_id = cfg.selected_node_id.clone();
        }
    } else if !cfg.remote.http3.device_credential_id.is_empty() {
        cfg.remote.transport="http2".into(); cfg.remote.host.clear(); cfg.remote.port=0;
        cfg.remote.http3=Http3Config::default(); cfg.node_selection_mode="manual".into();
        cfg.selected_node_id.clear(); cfg.preferred_node_id.clear();
    }
    cfg.normalize_nodes()?;
    if tun_running && previous_hosts != hosts(&cfg) {
        return Err("节点地址列表变化需要重新配置 TUN 路由，请先断开连接再同步".into());
    }
    let content = leases.iter().map(|(id,value)| format!("{id}={value}\n")).collect::<String>();
    config::replace_with_commit(&dir.join("leases.properties"), &content, || config::save_proxy_config(&cfg))?;
    Ok(leases.len())
}

#[tauri::command]
pub async fn subscription_sync(state: tauri::State<'_, SubscriptionState>,
    app: tauri::State<'_, std::sync::Arc<Mutex<crate::state::AppState>>>) -> Result<usize> {
    let _sync = state.0.lock().await;
    let device = identity()?.filter(|id| !id.device_id.is_empty()).ok_or("请先登录订阅账号")?;
    let id = &device.device_id;
    let nonce: Challenge = post(&format!("{id}/challenge"), json!({})).await?;
    let tls = SigningKey::random(&mut OsRng);
    let public = spki(&tls)?;
    let message = format!("simpleplane-manifest-v1\n{id}\n{}\n{public}",nonce.challenge);
    let manifest: Manifest = post(&format!("{id}/manifest"), json!({"challenge":nonce.challenge,
        "tlsPublicKey":public,"signature":signature(&signing_key(&device)?,&message)})).await?;
    let current = app.lock().await;
    install(manifest,tls.to_pkcs8_der().map_err(|_| "TLS 密钥生成失败")?.as_bytes(),
        current.tun_status == crate::state::ServiceStatus::Running)
}

#[tauri::command]
pub async fn subscription_bound() -> Result<bool> { Ok(identity()?.is_some_and(|v| !v.device_id.is_empty())) }

#[tauri::command]
pub async fn subscription_logout(state: tauri::State<'_, SubscriptionState>,
    app: tauri::State<'_, std::sync::Arc<Mutex<crate::state::AppState>>>) -> Result<()> {
    let mut pending = state.0.lock().await;
    let current = app.lock().await;
    install(Manifest {version:1, expires_at:now()+1, nodes:Vec::new()}, &[],
        current.tun_status == crate::state::ServiceStatus::Running)?;
    match entry("device")?.delete_credential() {
        Ok(()) | Err(keyring::Error::NoEntry) => (),
        Err(_) => return Err("无法清除系统设备凭据".into()),
    }
    *pending = None;
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn node(id: &str, port: u16, credential: &str) -> RemoteNode {
        RemoteNode {
            id: id.into(),
            name: id.into(),
            remote: RemoteConfig {
                host: "192.0.2.1".into(),
                port,
                cipher: "aes-gcm-v2".into(),
                key: String::new(),
                transport: "http3".into(),
                http3: Http3Config {
                    device_credential_id: credential.into(),
                    ..Default::default()
                },
            },
        }
    }

    #[test]
    fn matching_manual_selection_moves_to_the_authenticated_endpoint() {
        let nodes = [
            node("original", 9090, ""),
            node("different-port", 9091, ""),
            node("subscription-node", 9090, "node"),
        ];
        assert_eq!(replacement_selection(&nodes, "original").as_deref(), Some("subscription-node"));
        assert_eq!(replacement_selection(&nodes, "different-port"), None);
        assert_eq!(replacement_selection(&nodes, "subscription-node"), None);
    }
}
