package com.proxy.local.handler;

import com.proxy.local.config.ProxyConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Java 端路由决策引擎。
 *
 * <p>系统规则始终先于用户规则，用户规则遵循 first-match-wins。旧的
 * proxyList/directList 在构造阶段转换为规则，因此不会改变既有配置的行为。</p>
 */
public final class RouteEngine {

    private static final Logger log = LoggerFactory.getLogger(RouteEngine.class);
    private static final long RULE_VERSION = 1L;
    private static final Pattern IPV4 = Pattern.compile("[0-9]{1,3}(\\.[0-9]{1,3}){3}");
    private static final Pattern IPV6_LITERAL = Pattern.compile("[0-9a-fA-F:.%]+");

    private final List<CompiledRule> systemRules;
    private final List<CompiledRule> userRules;
    private final RouteAction defaultAction;
    private final long ruleVersion;

    public RouteEngine(ProxyConfig.RouteConfig config) {
        this(config, Collections.<String>emptyList());
    }

    public RouteEngine(ProxyConfig.RouteConfig config, Collection<String> proxyRemoteHosts) {
        ProxyConfig.RouteConfig safeConfig = config == null ? new ProxyConfig.RouteConfig() : config;
        this.systemRules = compileSystemRules(safeConfig, proxyRemoteHosts);
        this.userRules = compileUserRules(safeConfig);
        this.defaultAction = parseAction(safeConfig.getDefaultRoute(), "defaultRoute");
        this.ruleVersion = RULE_VERSION;
        log.info("RouteEngine initialized: systemRules={}, userRules={}, default={}, version={}",
                systemRules.size(), userRules.size(), defaultAction, ruleVersion);
    }

    public RouteDecision route(RouteContext context) {
        if (context == null) {
            return decision(defaultAction, "default", RouteDecision.Reason.DEFAULT);
        }
        // FakeIP 不能当成真实目标 IP 参与系统直连规则；应由 FakeDNS 先恢复域名。
        if (!context.isFakeIp()) {
            RouteDecision systemDecision = match(systemRules, context, RouteDecision.Reason.SYSTEM);
            if (systemDecision != null) return systemDecision;
        }
        RouteDecision userDecision = match(userRules, context, RouteDecision.Reason.USER);
        if (userDecision != null) return userDecision;
        return decision(defaultAction, "default", RouteDecision.Reason.DEFAULT);
    }

    /** 与设计文档中的“决策”命名保持一致，route() 作为底层入口保留。 */
    public RouteDecision decide(RouteContext context) {
        return route(context);
    }

    private RouteDecision match(List<CompiledRule> rules, RouteContext context,
                                RouteDecision.Reason reason) {
        for (CompiledRule rule : rules) {
            if (rule.matches(context)) {
                RouteDecision decision = decision(rule.action, rule.id, reason);
                log.debug("Route {}: {}", decision, targetForLog(context));
                return decision;
            }
        }
        return null;
    }

    private RouteDecision decision(RouteAction action, String id, RouteDecision.Reason reason) {
        return new RouteDecision(action, id, ruleVersion, reason);
    }

    private String targetForLog(RouteContext context) {
        return context.getDomain() == null ? context.getDestination() : context.getDomain();
    }

    private List<CompiledRule> compileSystemRules(ProxyConfig.RouteConfig config,
                                                   Collection<String> proxyRemoteHosts) {
        List<CompiledRule> rules = new ArrayList<>();
        int index = 0;
        if (proxyRemoteHosts != null) {
            for (String host : proxyRemoteHosts) {
                String normalized = normalize(host);
                if (parseIp(normalized) != null) {
                    rules.add(new CompiledRule("system-proxy-remote-" + index++, MatchType.IP,
                            normalized, RouteAction.DIRECT));
                }
            }
        }
        // 这些地址不能通过本地代理再次转发，否则会形成代理回环。
        rules.add(new CompiledRule("system-loopback", MatchType.SYSTEM_LOCAL, "", RouteAction.DIRECT));
        rules.add(new CompiledRule("system-private-network", MatchType.SYSTEM_PRIVATE, "", RouteAction.DIRECT));
        for (String pattern : safeList(config.getSystemDirectList())) {
            CompiledRule rule = compilePattern("system-direct-" + index++, pattern, RouteAction.DIRECT);
            if (rule != null) rules.add(rule);
        }
        return rules;
    }

    private List<CompiledRule> compileUserRules(ProxyConfig.RouteConfig config) {
        List<CompiledRule> rules = new ArrayList<>();
        int index = 0;
        // 新规则在前，允许用户逐步迁移；旧列表仍保持 directList 优先于 proxyList。
        for (ProxyConfig.RouteConfig.RouteEntry entry : safeEntries(config.getRules())) {
            if (entry == null) continue;
            String id = emptyToDefault(entry.getId(), "rule-" + index);
            RouteAction action = parseAction(entry.getAction(), id);
            rules.add(compileEntry(id, entry.getType(), entry.getValue(), action));
            index++;
        }
        for (String pattern : safeList(config.getDirectList())) {
            CompiledRule rule = compilePattern("legacy-direct-" + index++, pattern, RouteAction.DIRECT);
            if (rule != null) rules.add(rule);
        }
        for (String provider : safeList(config.getProxyProviders())) {
            CompiledRule rule = loadProxyProvider(provider);
            if (rule != null) rules.add(rule);
        }
        for (String pattern : safeList(config.getProxyList())) {
            CompiledRule rule = compilePattern("legacy-proxy-" + index++, pattern, RouteAction.PROXY);
            if (rule != null) rules.add(rule);
        }
        return rules;
    }

    private CompiledRule compileEntry(String id, String type, String value, RouteAction action) {
        MatchType matchType;
        try {
            matchType = MatchType.valueOf(type == null ? "" : type.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown route rule type for " + id + ": " + type, e);
        }
        String normalized = normalize(value);
        if (matchType == MatchType.MATCH) normalized = "";
        else if (normalized == null) throw new IllegalArgumentException("Route rule value must not be empty: " + id);
        if (matchType == MatchType.PROTOCOL && !"tcp".equals(normalized) && !"udp".equals(normalized)) {
            throw new IllegalArgumentException("Invalid protocol for " + id + ": " + value);
        }
        return new CompiledRule(id, matchType, normalized, action);
    }

    private CompiledRule compilePattern(String id, String pattern, RouteAction action) {
        String normalized = normalize(pattern);
        if (normalized == null) return null;
        if (parseIp(normalized) != null) return new CompiledRule(id, MatchType.IP, normalized, action);
        if (normalized.indexOf('/') > 0) return new CompiledRule(id, MatchType.IP_CIDR, normalized, action);
        if (normalized.startsWith("*.")) {
            return new CompiledRule(id, MatchType.DOMAIN_SUFFIX, normalized.substring(2), action);
        }
        return new CompiledRule(id, MatchType.DOMAIN_FULL, normalized, action);
    }

    /**
     * 加载本地代理域名 provider。provider 只在启动编译阶段读取，匹配阶段不做磁盘或网络 I/O。
     * 每行一个域名，按域名后缀语义匹配自身及所有子域名。
     */
    private CompiledRule loadProxyProvider(String source) {
        if (source == null || source.trim().isEmpty()) return null;
        Set<String> suffixes = new HashSet<>();
        try (InputStream input = openProvider(source);
             BufferedReader reader = new BufferedReader(new InputStreamReader(input, "UTF-8"))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String domain = line.trim();
                if (domain.isEmpty() || domain.startsWith("#") || domain.startsWith(";")) continue;
                int comment = domain.indexOf('#');
                if (comment >= 0) domain = domain.substring(0, comment).trim();
                if (domain.startsWith("*.")) domain = domain.substring(2);
                if (domain.startsWith("+.")) domain = domain.substring(2);
                if (domain.startsWith("domain:")) domain = domain.substring("domain:".length());
                if (domain.startsWith("domain_suffix:")) domain = domain.substring("domain_suffix:".length());
                domain = normalize(domain);
                if (domain != null && domain.indexOf('.') > 0 && parseIp(domain) == null) {
                    suffixes.add(domain);
                }
            }
        } catch (IOException e) {
            throw new IllegalArgumentException("Failed to load route provider: " + source, e);
        }
        if (suffixes.isEmpty()) return null;
        return new CompiledRule("provider-" + source, suffixes, RouteAction.PROXY);
    }

    private InputStream openProvider(String source) throws IOException {
        String path = source.trim();
        if (path.startsWith("classpath:")) {
            path = path.substring("classpath:".length());
            while (path.startsWith("/")) path = path.substring(1);
            InputStream input = RouteEngine.class.getClassLoader().getResourceAsStream(path);
            if (input == null) throw new IOException("Classpath resource not found: " + path);
            return input;
        }
        return java.nio.file.Files.newInputStream(java.nio.file.Paths.get(path));
    }

    private RouteAction parseAction(String action, String ruleId) {
        String normalized = action == null ? "" : action.trim().toLowerCase(Locale.ROOT);
        if ("direct".equals(normalized)) return RouteAction.DIRECT;
        if ("proxy".equals(normalized)) return RouteAction.PROXY;
        if ("reject".equals(normalized)) return RouteAction.REJECT;
        throw new IllegalArgumentException("Invalid route action for " + ruleId + ": " + action);
    }

    private boolean matches(CompiledRule rule, RouteContext context) {
        String domain = context.getDomain();
        String destination = context.getDestination();
        switch (rule.type) {
            case DOMAIN_FULL:
                return domain != null && domain.equals(rule.value);
            case DOMAIN_SUFFIX:
                return domain != null && (domain.equals(rule.value) || domain.endsWith("." + rule.value));
            case DOMAIN_SUFFIX_SET:
                return matchesDomainSuffixSet(domain, rule.values);
            case DOMAIN_KEYWORD:
                return domain != null && domain.contains(rule.value);
            case IP_CIDR:
                return matchesCidr(destination, rule.cidr);
            case IP:
                return destination != null && destination.equals(rule.value);
            case PORT:
                return context.getPort() >= rule.portStart && context.getPort() <= rule.portEnd;
            case PROTOCOL:
                return context.getProtocol().name().toLowerCase(Locale.ROOT).equals(rule.value);
            case MATCH:
                return true;
            case SYSTEM_LOCAL:
                return isLoopback(context);
            case SYSTEM_PRIVATE:
                return isPrivate(context);
            default:
                return false;
        }
    }

    private boolean matchesDomainSuffixSet(String domain, Set<String> suffixes) {
        if (domain == null) return false;
        String candidate = domain;
        while (candidate != null) {
            if (suffixes.contains(candidate)) return true;
            int dot = candidate.indexOf('.');
            candidate = dot < 0 ? null : candidate.substring(dot + 1);
        }
        return false;
    }

    private boolean isLoopback(RouteContext context) {
        if ("localhost".equals(context.getDomain())) return true;
        InetAddress address = parseIp(context.getDestination());
        return address != null && address.isLoopbackAddress();
    }

    private boolean isPrivate(RouteContext context) {
        InetAddress address = parseIp(context.getDestination());
        return address != null && (address.isAnyLocalAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || isUniqueLocalIpv6(address));
    }

    private boolean matchesCidr(String destination, Cidr parsed) {
        InetAddress address = parseIp(destination);
        if (address == null) return false;
        if (address.getAddress().length != parsed.network.getAddress().length) return false;
        byte[] actual = address.getAddress();
        byte[] network = parsed.network.getAddress();
        for (int i = 0; i < actual.length; i++) {
            int mask = i < parsed.prefix / 8 ? 0xFF
                    : (i == parsed.prefix / 8 && parsed.prefix % 8 != 0
                    ? (0xFF << (8 - parsed.prefix % 8)) & 0xFF : 0);
            if ((actual[i] & mask) != (network[i] & mask)) return false;
        }
        return true;
    }

    private Cidr parseCidr(String value, String ruleId) {
        String[] parts = value.split("/", -1);
        if (parts.length > 2 || parts.length == 0) {
            throw new IllegalArgumentException("Invalid CIDR for " + ruleId + ": " + value);
        }
        InetAddress network = parseIp(parts[0]);
        if (network == null) throw new IllegalArgumentException("Invalid IP in CIDR for " + ruleId + ": " + value);
        int max = network.getAddress().length * 8;
        int prefix = parts.length == 1 ? max : parseInt(parts[1], ruleId);
        if (prefix < 0 || prefix > max) throw new IllegalArgumentException("Invalid CIDR prefix for " + ruleId + ": " + value);
        return new Cidr(network, prefix);
    }

    private int[] parsePortRange(String value, String ruleId) {
        String[] parts = value.split("-", -1);
        if (parts.length > 2) throw new IllegalArgumentException("Invalid port for " + ruleId + ": " + value);
        int start = parseInt(parts[0], ruleId);
        int end = parts.length == 1 ? start : parseInt(parts[1], ruleId);
        if (start < 1 || end > 65535 || start > end) throw new IllegalArgumentException("Invalid port for " + ruleId + ": " + value);
        return new int[]{start, end};
    }

    private int parseInt(String value, String ruleId) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid number for " + ruleId + ": " + value, e);
        }
    }

    private InetAddress parseIp(String value) {
        if (value == null) return null;
        String candidate = value;
        if (candidate.startsWith("[") && candidate.endsWith("]")) {
            candidate = candidate.substring(1, candidate.length() - 1);
        }
        if (!IPV4.matcher(candidate).matches()
                && !(candidate.indexOf(':') >= 0 && IPV6_LITERAL.matcher(candidate).matches())) return null;
        if (candidate.indexOf('%') >= 0) candidate = candidate.substring(0, candidate.indexOf('%'));
        try {
            InetAddress address = InetAddress.getByName(candidate);
            if (IPV4.matcher(candidate).matches() && address.getAddress().length != 4) return null;
            return address;
        } catch (UnknownHostException e) {
            return null;
        }
    }

    private boolean isUniqueLocalIpv6(InetAddress address) {
        byte[] bytes = address.getAddress();
        return bytes.length == 16 && (bytes[0] & 0xFE) == 0xFC;
    }

    private String normalize(String value) {
        if (value == null) return null;
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        while (normalized.endsWith(".")) normalized = normalized.substring(0, normalized.length() - 1);
        return normalized.isEmpty() ? null : normalized;
    }

    private String emptyToDefault(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
    }

    private List<String> safeList(List<String> values) {
        return values == null ? Collections.<String>emptyList() : values;
    }

    private List<ProxyConfig.RouteConfig.RouteEntry> safeEntries(
            List<ProxyConfig.RouteConfig.RouteEntry> values) {
        return values == null ? Collections.<ProxyConfig.RouteConfig.RouteEntry>emptyList() : values;
    }

    private enum MatchType {
        DOMAIN_FULL, DOMAIN_SUFFIX, DOMAIN_SUFFIX_SET, DOMAIN_KEYWORD, IP_CIDR, IP, PORT, PROTOCOL,
        MATCH, SYSTEM_LOCAL, SYSTEM_PRIVATE
    }

    private final class CompiledRule {
        private final String id;
        private final MatchType type;
        private final String value;
        private final RouteAction action;
        private final Set<String> values;
        private final Cidr cidr;
        private final int portStart;
        private final int portEnd;

        private CompiledRule(String id, MatchType type, String value, RouteAction action) {
            this.id = id;
            this.type = type;
            this.value = value;
            this.action = action;
            this.values = null;
            this.cidr = type == MatchType.IP_CIDR ? parseCidr(value, id) : null;
            if (type == MatchType.PORT) {
                int[] range = parsePortRange(value, id);
                this.portStart = range[0];
                this.portEnd = range[1];
            } else {
                this.portStart = 0;
                this.portEnd = 0;
            }
        }

        private CompiledRule(String id, Set<String> values, RouteAction action) {
            this.id = id;
            this.type = MatchType.DOMAIN_SUFFIX_SET;
            this.value = null;
            this.action = action;
            this.values = Collections.unmodifiableSet(values);
            this.cidr = null;
            this.portStart = 0;
            this.portEnd = 0;
        }

        private boolean matches(RouteContext context) {
            return RouteEngine.this.matches(this, context);
        }
    }

    private static final class Cidr {
        private final InetAddress network;
        private final int prefix;

        private Cidr(InetAddress network, int prefix) {
            this.network = network;
            this.prefix = prefix;
        }
    }
}
