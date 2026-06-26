package com.proxy.local.handler;

import com.proxy.local.config.ProxyConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * 路由规则匹配器 —— 根据域名判断走代理还是直连
 * <p>
 * 匹配优先级：
 * 1. directList 强制直连（最高优先级）
 * 2. proxyList 走代理
 * 3. defaultRoute 决定默认行为
 * </p>
 */
public class RouteRule {

    private static final Logger log = LoggerFactory.getLogger(RouteRule.class);

    private final String defaultRoute;
    private final List<String> proxyPatterns;
    private final List<String> directPatterns;
    private final boolean tunMode;

    public RouteRule(ProxyConfig.RouteConfig config) {
        this(config, false);
    }

    public RouteRule(ProxyConfig.RouteConfig config, boolean tunMode) {
        this.defaultRoute = config.getDefaultRoute();
        this.proxyPatterns = new ArrayList<>(config.getProxyList());
        this.directPatterns = new ArrayList<>(config.getDirectList());
        this.tunMode = tunMode;
        log.info("RouteRule initialized: default={}, proxyRules={}, directRules={}, tunMode={}",
                defaultRoute, proxyPatterns.size(), directPatterns.size(), tunMode);
    }

    /**
     * 判断目标域名是否应该走代理
     *
     * @param host 目标域名
     * @return true=走远程代理, false=直连
     */
    public boolean shouldProxy(String host) {
        // TUN 模式下所有流量统一走 proxy-remote，避免 DirectRelayHandler 回环
        if (tunMode) {
            return true;
        }

        if (host == null || host.isEmpty()) {
            return "proxy".equals(defaultRoute);
        }

        String lowerHost = host.toLowerCase();

        // 1. directList 优先级最高
        for (String pattern : directPatterns) {
            if (matchPattern(lowerHost, pattern.toLowerCase())) {
                log.debug("Route DIRECT (directList match: {}): {}", pattern, host);
                return false;
            }
        }

        // 2. proxyList 次之
        for (String pattern : proxyPatterns) {
            if (matchPattern(lowerHost, pattern.toLowerCase())) {
                log.debug("Route PROXY (proxyList match: {}): {}", pattern, host);
                return true;
            }
        }

        // 3. 默认路由
        boolean useProxy = "proxy".equals(defaultRoute);
        log.debug("Route {} (default): {}", useProxy ? "PROXY" : "DIRECT", host);
        return useProxy;
    }

    /**
     * 通配符匹配
     * 支持:
     *   *.google.com  → 匹配 www.google.com, mail.google.com 等
     *   google.com    → 精确匹配 google.com
     */
    private boolean matchPattern(String host, String pattern) {
        if (pattern.startsWith("*.")) {
            // 通配符：匹配子域名或自身
            String suffix = pattern.substring(1); // ".google.com"
            return host.endsWith(suffix) || host.equals(pattern.substring(2));
        } else {
            // 精确匹配
            return host.equals(pattern);
        }
    }
}
