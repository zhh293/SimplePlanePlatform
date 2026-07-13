use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Deserialize, Serialize)]
pub struct AndroidConfig {
    #[serde(default = "default_mtu")]
    pub mtu: usize,

    #[serde(default)]
    pub remote_host: String,
    #[serde(default)]
    pub remote_port: u16,
    #[serde(default)]
    pub remote_key: String,
    #[serde(default = "default_cipher")]
    pub cipher: String,
    #[serde(default)]
    pub tls: bool,

    #[serde(default, alias = "remoteServers")]
    pub remotes: Vec<RemoteNode>,
    #[serde(default)]
    pub routing: RoutingConfig,
    #[serde(default)]
    pub route: DesktopRouteConfig,
}

#[derive(Debug, Clone, Deserialize, Serialize)]
pub struct RemoteNode {
    #[serde(default = "default_node_name")]
    pub name: String,
    #[serde(default, alias = "remote_host")]
    pub host: String,
    #[serde(default, alias = "remote_port")]
    pub port: u16,
    #[serde(default, alias = "cipherKey", alias = "remote_key", alias = "password")]
    pub key: String,
    #[serde(default = "default_cipher")]
    pub cipher: String,
    #[serde(default, alias = "ssl")]
    pub tls: bool,
    #[serde(default = "default_enabled")]
    pub enabled: bool,
}

#[derive(Debug, Clone, Deserialize, Serialize)]
pub struct RoutingConfig {
    #[serde(default = "default_route_action", alias = "defaultAction")]
    pub default_action: String,
    #[serde(default = "default_cn_direct", alias = "cnDirect")]
    pub cn_direct: bool,
    #[serde(default)]
    pub rules: Vec<RuleConfig>,
}

#[derive(Debug, Clone, Deserialize, Serialize)]
pub struct RuleConfig {
    #[serde(rename = "type", alias = "rule_type")]
    pub rule_type: String,
    pub value: String,
    pub action: String,
}

#[derive(Debug, Clone, Deserialize, Serialize)]
pub struct DesktopRouteConfig {
    #[serde(default = "default_route_action", alias = "defaultRoute")]
    pub default_route: String,
    #[serde(default, alias = "proxyList")]
    pub proxy_list: Vec<String>,
    #[serde(default, alias = "directList")]
    pub direct_list: Vec<String>,
}

fn default_mtu() -> usize {
    1500
}

fn default_cipher() -> String {
    "chacha20".to_string()
}

fn default_node_name() -> String {
    "default".to_string()
}

fn default_enabled() -> bool {
    true
}

fn default_route_action() -> String {
    "proxy".to_string()
}

fn default_cn_direct() -> bool {
    true
}

impl Default for AndroidConfig {
    fn default() -> Self {
        Self {
            mtu: default_mtu(),
            remote_host: String::new(),
            remote_port: 0,
            remote_key: String::new(),
            cipher: default_cipher(),
            tls: false,
            remotes: Vec::new(),
            routing: RoutingConfig::default(),
            route: DesktopRouteConfig::default(),
        }
    }
}

impl Default for RoutingConfig {
    fn default() -> Self {
        Self {
            default_action: default_route_action(),
            cn_direct: default_cn_direct(),
            rules: Vec::new(),
        }
    }
}

impl Default for DesktopRouteConfig {
    fn default() -> Self {
        Self {
            default_route: default_route_action(),
            proxy_list: Vec::new(),
            direct_list: Vec::new(),
        }
    }
}

impl RemoteNode {
    pub fn ready(&self) -> bool {
        self.enabled && !self.host.is_empty() && self.port != 0 && !self.key.is_empty()
    }
}

impl AndroidConfig {
    pub fn from_json(json: &str) -> crate::error::Result<Self> {
        let trimmed = json.trim();
        if trimmed.is_empty() {
            return Ok(Self::default());
        }
        Ok(serde_json::from_str(trimmed)?)
    }

    pub fn outbound_ready(&self) -> bool {
        !self.normalized_remotes().is_empty()
    }

    pub fn normalized_remotes(&self) -> Vec<RemoteNode> {
        let nodes: Vec<RemoteNode> = self
            .remotes
            .iter()
            .filter(|node| node.ready())
            .cloned()
            .collect();
        if !nodes.is_empty() {
            return nodes;
        }

        let legacy = RemoteNode {
            name: "default".to_string(),
            host: self.remote_host.clone(),
            port: self.remote_port,
            key: self.remote_key.clone(),
            cipher: self.cipher.clone(),
            tls: self.tls,
            enabled: true,
        };
        if legacy.ready() {
            vec![legacy]
        } else {
            Vec::new()
        }
    }

    pub fn normalized_routing(&self) -> RoutingConfig {
        let routing_is_custom = self.routing.default_action != default_route_action()
            || !self.routing.cn_direct
            || !self.routing.rules.is_empty();
        let legacy_route_is_custom = self.route.default_route != default_route_action()
            || !self.route.direct_list.is_empty()
            || !self.route.proxy_list.is_empty();

        // New Android configs use `routing`; only untouched defaults may fall back to `route`.
        if routing_is_custom || !legacy_route_is_custom {
            return self.routing.clone();
        }

        let mut rules = Vec::new();
        for item in &self.route.direct_list {
            if !item.trim().is_empty() {
                rules.push(RuleConfig {
                    rule_type: "domain_pattern".to_string(),
                    value: item.trim().to_string(),
                    action: "direct".to_string(),
                });
            }
        }
        for item in &self.route.proxy_list {
            if !item.trim().is_empty() {
                rules.push(RuleConfig {
                    rule_type: "domain_pattern".to_string(),
                    value: item.trim().to_string(),
                    action: "proxy".to_string(),
                });
            }
        }

        RoutingConfig {
            default_action: self.route.default_route.clone(),
            cn_direct: true,
            rules,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn empty_json_uses_defaults() {
        let c = AndroidConfig::from_json("").unwrap();
        assert_eq!(c.mtu, 1500);
        assert!(c.routing.cn_direct);
        assert!(!c.outbound_ready());
    }

    #[test]
    fn smart_cn_routing_can_be_disabled_explicitly() {
        let c = AndroidConfig::from_json(
            r#"{"routing":{"default_action":"proxy","cn_direct":false,"rules":[]}}"#,
        )
        .unwrap();
        assert!(!c.normalized_routing().cn_direct);
    }

    #[test]
    fn legacy_single_node_still_works() {
        let c = AndroidConfig::from_json(
            r#"{"remote_host":"1.2.3.4","remote_port":9090,"remote_key":"k"}"#,
        )
        .unwrap();
        let nodes = c.normalized_remotes();
        assert_eq!(nodes.len(), 1);
        assert_eq!(nodes[0].host, "1.2.3.4");
    }

    #[test]
    fn desktop_remote_servers_are_accepted() {
        let c = AndroidConfig::from_json(
            r#"{"remoteServers":[{"host":"a.example","port":9090,"cipherKey":"k","ssl":true}]}"#,
        )
        .unwrap();
        let nodes = c.normalized_remotes();
        assert_eq!(nodes.len(), 1);
        assert!(nodes[0].tls);
        assert_eq!(nodes[0].key, "k");
    }

    #[test]
    fn desktop_route_lists_become_ordered_rules() {
        let c = AndroidConfig::from_json(
            r#"{"route":{"defaultRoute":"proxy","directList":["*.cn"],"proxyList":["github.com"]}}"#,
        )
        .unwrap();
        let routing = c.normalized_routing();
        assert_eq!(routing.default_action, "proxy");
        assert_eq!(routing.rules.len(), 2);
        assert_eq!(routing.rules[0].action, "direct");
        assert_eq!(routing.rules[1].action, "proxy");
    }
}
