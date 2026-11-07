//! Desktop and proxy-local share one route policy. Compile off the packet loop;
//! publish complete snapshots so live edits only affect new connections.
use crate::{
    config::{RoutingConfig, RuleConfig},
    router::{ConnectionInfo, Router},
};
use serde::Deserialize;
use std::{
    path::{Path, PathBuf},
    sync::{Arc, RwLock},
    time::Duration,
};

#[derive(Deserialize)]
struct PolicyFile {
    route: SharedPolicy,
}

#[derive(Deserialize)]
struct SharedPolicy {
    #[serde(default = "default_action", alias = "defaultRoute")]
    default_route: String,
    #[serde(default, alias = "directList")]
    direct_list: Vec<String>,
    #[serde(default, alias = "proxyList")]
    proxy_list: Vec<String>,
    #[serde(default = "direct_providers", alias = "directProviders")]
    direct_providers: Vec<String>,
    #[serde(default = "proxy_providers", alias = "proxyProviders")]
    proxy_providers: Vec<String>,
    #[serde(default, alias = "systemDirectList")]
    system_direct_list: Vec<String>,
    #[serde(default)]
    rules: Vec<RuleConfig>,
}
fn default_action() -> String {
    "proxy".into()
}
fn direct_providers() -> Vec<String> {
    vec!["classpath:routing/providers/direct-list.txt".into()]
}
fn proxy_providers() -> Vec<String> {
    vec!["classpath:routing/providers/proxy-list.txt".into()]
}

fn pattern(value: &str, action: &str) -> Option<RuleConfig> {
    let value = value.trim().trim_end_matches('.').to_lowercase();
    if value.is_empty() {
        return None;
    }
    let (kind, value) = if value.parse::<std::net::IpAddr>().is_ok() || value.starts_with('[') {
        ("ip", value)
    } else if value.contains('/') {
        ("ip_cidr", value)
    } else if let Some(suffix) = value.strip_prefix("*.") {
        ("domain_suffix", suffix.to_string())
    } else {
        ("domain_full", value)
    };
    Some(RuleConfig {
        rule_type: kind.into(),
        value,
        action: action.into(),
    })
}

fn load(path: &Path) -> Result<(Router, Vec<PathBuf>), String> {
    let text = std::fs::read_to_string(path)
        .map_err(|e| format!("Cannot read route policy {}: {e}", path.display()))?;
    let policy: PolicyFile = serde_yaml::from_str(&text)
        .map_err(|e| format!("Invalid route policy {}: {e}", path.display()))?;
    let mut rules = Vec::new();
    let mut watched = vec![path.to_path_buf()];
    for value in policy.route.system_direct_list {
        rules.extend(pattern(&value, "direct"));
    }
    rules.extend(policy.route.rules);
    for value in policy.route.direct_list {
        rules.extend(pattern(&value, "direct"));
    }
    // Keep the same precedence as Java RouteEngine: explicit rules, direct list,
    // direct providers, proxy providers, proxy list, then default.
    for (providers, action) in [
        (policy.route.direct_providers, "direct"),
        (policy.route.proxy_providers, "proxy"),
    ] {
        for value in providers {
            let value = value.trim();
            if value.is_empty() {
                continue;
            }
            if !value.starts_with("classpath:") {
                watched.push(PathBuf::from(value));
            }
            rules.push(RuleConfig {
                rule_type: "domain_provider".into(),
                value: value.into(),
                action: action.into(),
            });
        }
    }
    for value in policy.route.proxy_list {
        rules.extend(pattern(&value, "proxy"));
    }
    let config = RoutingConfig {
        default_action: policy.route.default_route,
        rules,
    };
    let router = Router::from_config(&config).map_err(|e| e.to_string())?;
    Ok((router, watched))
}

pub struct RoutePolicy {
    current: RwLock<Arc<Router>>,
}
impl RoutePolicy {
    pub fn new(
        fallback: &RoutingConfig,
        source: Option<&Path>,
    ) -> Result<(Self, Vec<PathBuf>), String> {
        let (router, paths) = match source {
            Some(path) => load(path)?,
            None => (
                Router::from_config(fallback).map_err(|e| e.to_string())?,
                Vec::new(),
            ),
        };
        Ok((
            Self {
                current: RwLock::new(Arc::new(router)),
            },
            paths,
        ))
    }
    pub fn route(&self, info: &ConnectionInfo) -> crate::router::RouteAction {
        let snapshot = Arc::clone(&self.current.read().unwrap());
        snapshot.route(info)
    }
    fn reload(&self, source: &Path) -> Result<Vec<PathBuf>, String> {
        let (router, paths) = load(source)?;
        *self.current.write().unwrap() = Arc::new(router);
        Ok(paths)
    }
}

fn stamp(paths: &[PathBuf]) -> Vec<Option<(u64, Option<std::time::SystemTime>)>> {
    paths
        .iter()
        .map(|p| {
            std::fs::metadata(p)
                .ok()
                .map(|m| (m.len(), m.modified().ok()))
        })
        .collect()
}

pub async fn watch(policy: Arc<RoutePolicy>, source: PathBuf, mut paths: Vec<PathBuf>) {
    // Recheck on the first tick: a save may have happened while TUN startup was
    // configuring the device after the initial policy compilation.
    let mut previous = vec![None; paths.len()];
    let mut retry_at = None;
    let mut interval = tokio::time::interval(Duration::from_secs(1));
    interval.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Skip);
    loop {
        interval.tick().await;
        let next = stamp(&paths);
        let changed = next != previous;
        let retry_due = retry_at.is_some_and(|at| std::time::Instant::now() >= at);
        if !changed && !retry_due {
            continue;
        }
        previous = next;
        let policy = policy.clone();
        let source_for_load = source.clone();
        match tokio::task::spawn_blocking(move || policy.reload(&source_for_load)).await {
            Ok(Ok(updated)) => {
                retry_at = None;
                // Retain the pre-load stamp. An edit during compilation must trigger
                // another reload rather than being mistaken for the published version.
                if paths != updated {
                    previous = vec![None; updated.len()];
                }
                paths = updated;
                tracing::info!(
                    "Shared route policy reloaded from {}; new connections use the updated rules",
                    source.display()
                );
            }
            Ok(Err(error)) => {
                retry_at = Some(std::time::Instant::now() + Duration::from_secs(5));
                if changed { tracing::warn!("Route reload rejected; keeping last valid rules: {error}"); }
            }
            Err(error) => {
                retry_at = Some(std::time::Instant::now() + Duration::from_secs(5));
                if changed { tracing::warn!("Route reload worker failed; keeping last valid rules: {error}"); }
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::router::{Protocol, RouteAction};
    fn info(domain: &str) -> ConnectionInfo {
        ConnectionInfo {
            src_ip: "198.18.0.1".parse().unwrap(),
            dst_ip: "198.19.0.10".parse().unwrap(),
            dst_port: 443,
            domain: Some(domain.into()),
            protocol: Protocol::Tcp,
        }
    }
    #[test]
    fn desktop_providers_cover_domestic_pages_and_cdns() {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("proxy.yml");
        std::fs::write(
            &path,
            "route:\n  default_route: proxy\n  direct_list: [bilibili.com]\n",
        )
        .unwrap();
        let (policy, _) = RoutePolicy::new(&RoutingConfig::default(), Some(&path)).unwrap();
        for host in [
            "www.bilibili.com",
            "i1.hdslb.com",
            "bvc.bilivideo.com",
            "cm.bilibili.com",
            "www.taobao.com",
            "www.jd.com",
            "www.qq.com",
            "www.baidu.com",
        ] {
            assert_eq!(policy.route(&info(host)), RouteAction::Direct, "{host}");
        }
        for host in ["www.google.com", "www.youtube.com", "api.openai.com"] {
            assert_eq!(policy.route(&info(host)), RouteAction::Proxy, "{host}");
        }
    }
    #[test]
    fn shared_explicit_rules_override_provider_and_stale_tun_rules() {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("proxy.yml");
        std::fs::write(&path, "route:\n  default_route: direct\n  rules:\n    - {type: domain_suffix, value: hdslb.com, action: proxy}\n    - {type: domain_full, value: blocked.example, action: reject}\n").unwrap();
        let fallback = RoutingConfig {
            default_action: "reject".into(),
            rules: vec![RuleConfig {
                rule_type: "domain_suffix".into(),
                value: "hdslb.com".into(),
                action: "direct".into(),
            }],
        };
        let (policy, _) = RoutePolicy::new(&fallback, Some(&path)).unwrap();
        assert_eq!(policy.route(&info("i1.hdslb.com")), RouteAction::Proxy);
        assert_eq!(policy.route(&info("blocked.example")), RouteAction::Reject);
        assert_eq!(policy.route(&info("unlisted.invalid")), RouteAction::Direct);
    }
    #[test]
    fn reload_is_atomic_and_invalid_provider_keeps_last_good_policy() {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("proxy.yml");
        let config = |action: &str| {
            format!(
                "route:\n  defaultRoute: {action}\n  directProviders: []\n  proxyProviders: []\n"
            )
        };
        std::fs::write(&path, config("proxy")).unwrap();
        let (policy, _) = RoutePolicy::new(&RoutingConfig::default(), Some(&path)).unwrap();
        let held_snapshot = policy.current.read().unwrap().clone();
        std::fs::write(&path, config("direct")).unwrap();
        policy.reload(&path).unwrap();
        assert_eq!(policy.route(&info("unlisted.invalid")), RouteAction::Direct);
        assert_eq!(
            held_snapshot.route(&info("unlisted.invalid")),
            RouteAction::Proxy
        );
        std::fs::write(
            &path,
            "route:\n  directProviders: [/missing/provider.txt]\n",
        )
        .unwrap();
        assert!(policy.reload(&path).is_err());
        assert_eq!(policy.route(&info("unlisted.invalid")), RouteAction::Direct);
    }
    #[test]
    fn legacy_lists_keep_exact_and_wildcard_semantics_and_direct_precedence() {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("proxy.yml");
        std::fs::write(&path, "route:\n  default_route: proxy\n  direct_list: [example.com, '*.direct.test', conflict.test]\n  proxy_list: [conflict.test]\n  direct_providers: []\n  proxy_providers: []\n").unwrap();
        let (policy, _) = RoutePolicy::new(&RoutingConfig::default(), Some(&path)).unwrap();
        for host in [
            "EXAMPLE.COM.",
            "a.direct.test",
            "direct.test",
            "conflict.test",
        ] {
            assert_eq!(policy.route(&info(host)), RouteAction::Direct, "{host}");
        }
        assert_eq!(policy.route(&info("sub.example.com")), RouteAction::Proxy);
    }
    #[tokio::test]
    async fn file_watcher_applies_saved_policy_to_new_decisions() {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("proxy.yml");
        let text = |action| {
            format!("route:\n  default_route: {action}\n  direct_providers: []\n  proxy_providers: []\n")
        };
        std::fs::write(&path, text("proxy")).unwrap();
        let (policy, paths) = RoutePolicy::new(&RoutingConfig::default(), Some(&path)).unwrap();
        let policy = Arc::new(policy);
        let watcher = tokio::spawn(watch(policy.clone(), path.clone(), paths));
        tokio::task::yield_now().await;
        std::fs::write(&path, text("reject")).unwrap();
        tokio::time::timeout(Duration::from_secs(4), async {
            while policy.route(&info("unlisted.invalid")) != RouteAction::Reject {
                tokio::time::sleep(Duration::from_millis(20)).await;
            }
        })
        .await
        .unwrap();
        watcher.abort();
    }
    #[test]
    fn provider_full_entries_boundaries_comments_and_live_file_changes() {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("proxy.yml");
        let provider = dir.path().join("domains.txt");
        std::fs::write(
            &provider,
            "# comment\n; comment\n+.Example.COM. # suffix\nfull:EXACT.test\ndomain:other.test\n",
        )
        .unwrap();
        std::fs::write(
            &path,
            format!(
                "route:\n  direct_providers: [{}]\n  proxy_providers: []\n",
                serde_yaml::to_string(&provider.to_string_lossy())
                    .unwrap()
                    .trim()
            ),
        )
        .unwrap();
        let (policy, watched) = RoutePolicy::new(&RoutingConfig::default(), Some(&path)).unwrap();
        assert!(watched.contains(&provider));
        for host in ["a.example.com", "EXACT.TEST.", "other.test"] {
            assert_eq!(policy.route(&info(host)), RouteAction::Direct, "{host}");
        }
        for host in ["notexample.com", "sub.exact.test"] {
            assert_eq!(policy.route(&info(host)), RouteAction::Proxy, "{host}");
        }
        std::fs::write(&provider, "replacement.test\n").unwrap();
        policy.reload(&path).unwrap();
        assert_eq!(policy.route(&info("example.com")), RouteAction::Proxy);
        assert_eq!(
            policy.route(&info("sub.replacement.test")),
            RouteAction::Direct
        );
    }
}
