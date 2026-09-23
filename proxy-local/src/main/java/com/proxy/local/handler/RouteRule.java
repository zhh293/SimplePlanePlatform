package com.proxy.local.handler;

import com.proxy.local.config.ProxyConfig;

import java.util.Collection;

/**
 * 旧路由接口的兼容适配器。
 *
 * <p>新代码应使用 {@link RouteEngine} 的结构化 {@link RouteDecision}；保留
 * {@link #shouldProxy(String)} 是为了不破坏现有调用方和配置迁移。</p>
 */
public class RouteRule {

    private final RouteEngine engine;

    public RouteRule(ProxyConfig.RouteConfig config) {
        this(config, null);
    }

    public RouteRule(ProxyConfig.RouteConfig config, Collection<String> proxyRemoteHosts) {
        this.engine = new RouteEngine(config, proxyRemoteHosts);
    }

    public RouteDecision decide(String host, int port) {
        return decide(host, port, RouteContext.Protocol.TCP);
    }

    public RouteDecision decide(String host, int port, RouteContext.Protocol protocol) {
        String destination = isIpLiteral(host) ? host : null;
        return engine.route(new RouteContext(host, destination, port, protocol));
    }

    public RouteDecision decide(RouteContext context) {
        return engine.route(context);
    }

    /**
     * 兼容旧调用方：只有 PROXY 返回 true。REJECT 由新 Handler 通过 decide() 处理。
     */
    public boolean shouldProxy(String host) {
        return decide(host, 0).isProxy();
    }

    public boolean shouldProxy(String host, int port) {
        return decide(host, port).isProxy();
    }

    public RouteDecision route(RouteContext context) {
        return engine.route(context);
    }

    /** 避免为了判断 IP 而触发 DNS 查询。 */
    private static boolean isIpLiteral(String value) {
        if (value == null) return false;
        String candidate = value;
        if (candidate.startsWith("[") && candidate.endsWith("]")) {
            candidate = candidate.substring(1, candidate.length() - 1);
        }
        if (candidate.matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}")) {
            String[] parts = candidate.split("\\.");
            for (String part : parts) {
                try {
                    if (Integer.parseInt(part) > 255) return false;
                } catch (NumberFormatException e) {
                    return false;
                }
            }
            return true;
        }
        return candidate.indexOf(':') >= 0 && candidate.matches("[0-9a-fA-F:.%]+");
    }
}
