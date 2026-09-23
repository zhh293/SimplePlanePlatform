package com.proxy.local.handler;

import com.proxy.local.config.ProxyConfig;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RouteEngineTest {

    @Test
    void legacyListsKeepDirectBeforeProxyCompatibility() {
        ProxyConfig.RouteConfig config = new ProxyConfig.RouteConfig();
        config.setDefaultRoute("proxy");
        config.setDirectList(Arrays.asList("*.example.com"));
        config.setProxyList(Arrays.asList("api.example.com"));

        RouteRule rule = new RouteRule(config);

        assertEquals(RouteAction.DIRECT, rule.decide("api.example.com", 443).getAction());
        assertEquals(RouteAction.PROXY, rule.decide("api.example.net", 443).getAction());
        assertEquals(RouteAction.PROXY, rule.decide("example.net", 443).getAction());
    }

    @Test
    void firstMatchWinsAndSupportsAllSystemProxyRuleTypes() {
        ProxyConfig.RouteConfig config = new ProxyConfig.RouteConfig();
        config.setDefaultRoute("proxy");
        config.setRules(Arrays.asList(
                entry("company-direct", "domain_suffix", "example.com", "direct"),
                entry("public-reject", "ip_cidr", "198.51.100.0/24", "reject"),
                entry("https-proxy", "port", "443", "proxy"),
                entry("udp-direct", "protocol", "udp", "direct"),
                entry("fallback-reject", "match", "", "reject")
        ));

        RouteEngine engine = new RouteEngine(config);

        assertEquals(RouteAction.DIRECT, engine.route(new RouteContext(
                "www.example.com", null, 443, RouteContext.Protocol.TCP)).getAction());
        assertEquals(RouteAction.REJECT, engine.route(new RouteContext(
                null, "198.51.100.2", 80, RouteContext.Protocol.TCP)).getAction());
        assertEquals(RouteAction.PROXY, engine.route(new RouteContext(
                "public.test", "8.8.8.8", 443, RouteContext.Protocol.TCP)).getAction());
        assertEquals(RouteAction.DIRECT, engine.route(new RouteContext(
                "public.test", "8.8.8.8", 53, RouteContext.Protocol.UDP)).getAction());
    }

    @Test
    void systemRulesCannotBeOverriddenByUserProxyRules() {
        ProxyConfig.RouteConfig config = new ProxyConfig.RouteConfig();
        config.setDefaultRoute("proxy");
        config.setProxyList(Arrays.asList("localhost", "127.0.0.1", "10.0.0.0"));

        RouteRule rule = new RouteRule(config, Arrays.asList("203.0.113.10"));

        assertEquals(RouteAction.DIRECT, rule.decide("localhost", 1080).getAction());
        assertEquals(RouteAction.DIRECT, rule.decide("10.1.2.3", 80).getAction());
        assertEquals(RouteAction.DIRECT, rule.decide("203.0.113.10", 9090).getAction());
        assertEquals(RouteDecision.Reason.SYSTEM, rule.decide("203.0.113.10", 9090).getReason());
    }

    @Test
    void domainMatchingUsesLabelBoundariesAndNormalizesInput() {
        ProxyConfig.RouteConfig config = new ProxyConfig.RouteConfig();
        config.setDefaultRoute("direct");
        config.setRules(Arrays.asList(entry("suffix", "domain_suffix", "example.com", "proxy")));

        RouteEngine engine = new RouteEngine(config);

        assertEquals(RouteAction.PROXY, engine.route(new RouteContext(
                "WWW.Example.COM.", null, 443, RouteContext.Protocol.TCP)).getAction());
        assertEquals(RouteAction.DIRECT, engine.route(new RouteContext(
                "notexample.com", null, 443, RouteContext.Protocol.TCP)).getAction());
    }

    @Test
    void localProxyProviderIsLoadedAsSuffixSet() {
        ProxyConfig.RouteConfig config = new ProxyConfig.RouteConfig();
        config.setDefaultRoute("direct");
        config.setProxyProviders(Arrays.asList("classpath:routing/providers/proxy-list.txt"));

        RouteEngine engine = new RouteEngine(config);

        assertEquals(RouteAction.PROXY, engine.route(new RouteContext(
                "www.github.com", null, 443, RouteContext.Protocol.TCP)).getAction());
        assertEquals(RouteAction.DIRECT, engine.route(new RouteContext(
                "example.invalid", null, 443, RouteContext.Protocol.TCP)).getAction());
    }

    @Test
    void directProviderHasPriorityOverProxyProvider() {
        ProxyConfig.RouteConfig config = new ProxyConfig.RouteConfig();
        config.setDefaultRoute("proxy");
        config.setDirectProviders(Arrays.asList("classpath:routing/providers/direct-list.txt"));
        config.setProxyProviders(Arrays.asList("classpath:routing/providers/proxy-list.txt"));

        RouteEngine engine = new RouteEngine(config);

        assertEquals(RouteAction.DIRECT, engine.route(new RouteContext(
                "www.baidu.com", null, 443, RouteContext.Protocol.TCP)).getAction());
        assertEquals(RouteAction.PROXY, engine.route(new RouteContext(
                "www.github.com", null, 443, RouteContext.Protocol.TCP)).getAction());
    }

    @Test
    void bundledProxyConfigLoadsProviderWithoutChangingLegacyLists() {
        ProxyConfig config = ProxyConfig.loadFromClasspath("proxy.yml");
        RouteRule rule = new RouteRule(config.getRoute(),
                Arrays.asList(config.getRemoteServers().get(0).getHost()));

        assertEquals(RouteAction.PROXY, rule.decide("www.github.com", 443).getAction());
        assertEquals(RouteAction.DIRECT, rule.decide("www.baidu.com", 443).getAction());
    }

    private static ProxyConfig.RouteConfig.RouteEntry entry(String id, String type,
                                                              String value, String action) {
        ProxyConfig.RouteConfig.RouteEntry entry = new ProxyConfig.RouteConfig.RouteEntry();
        entry.setId(id);
        entry.setType(type);
        entry.setValue(value);
        entry.setAction(action);
        return entry;
    }
}
