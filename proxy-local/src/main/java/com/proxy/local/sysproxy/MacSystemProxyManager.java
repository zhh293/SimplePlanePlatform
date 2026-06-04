package com.proxy.local.sysproxy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

/**
 * macOS 系统代理管理
 * <p>
 * 通过 networksetup 命令设置/还原系统代理。
 * 支持同时设置 HTTP、HTTPS 和 SOCKS 代理。
 * </p>
 * <p>
 * 工作流程：
 * 1. enable() 时先记录所有活跃网络接口的原始代理状态
 * 2. 设置 HTTP/HTTPS/SOCKS 代理指向本地端口
 * 3. disable() 时还原到原始状态
 * </p>
 */
public class MacSystemProxyManager implements SystemProxyManager {

    private static final Logger log = LoggerFactory.getLogger(MacSystemProxyManager.class);

    private volatile boolean enabled = false;

    /**
     * 保存原始代理状态，用于还原
     */
    private final List<ProxyState> originalStates = new ArrayList<>();

    @Override
    public void enable(String host, int port) throws SystemProxyException {
        if (enabled) {
            log.warn("System proxy already enabled, skipping");
            return;
        }

        try {
            // 获取所有活跃的网络接口
            List<String> activeServices = getActiveNetworkServices();
            if (activeServices.isEmpty()) {
                throw new SystemProxyException("No active network service found");
            }

            log.info("Detected active network services: {}", activeServices);

            // 保存原始状态并设置代理
            for (String service : activeServices) {
                ProxyState state = saveCurrentState(service);
                originalStates.add(state);

                // 设置 HTTP 代理
                exec("networksetup", "-setwebproxy", service, host, String.valueOf(port));
                exec("networksetup", "-setwebproxystate", service, "on");

                // 设置 HTTPS 代理
                exec("networksetup", "-setsecurewebproxy", service, host, String.valueOf(port));
                exec("networksetup", "-setsecurewebproxystate", service, "on");

                // 设置 SOCKS 代理
                exec("networksetup", "-setsocksfirewallproxy", service, host, String.valueOf(port));
                exec("networksetup", "-setsocksfirewallproxystate", service, "on");

                log.info("System proxy set for [{}] -> {}:{}", service, host, port);
            }

            enabled = true;
            log.info("System proxy enabled successfully");

        } catch (SystemProxyException e) {
            throw e;
        } catch (Exception e) {
            throw new SystemProxyException("Failed to enable system proxy", e);
        }
    }

    @Override
    public void disable() throws SystemProxyException {
        if (!enabled) {
            return;
        }

        try {
            for (ProxyState state : originalStates) {
                restoreState(state);
                log.info("System proxy restored for [{}]", state.service);
            }
            originalStates.clear();
            enabled = false;
            log.info("System proxy disabled successfully");

        } catch (Exception e) {
            throw new SystemProxyException("Failed to disable system proxy", e);
        }
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * 获取当前活跃的网络接口名称列表
     * <p>
     * 通过 networksetup -listallnetworkservices 获取所有接口，
     * 然后检查每个接口是否有有效的 IP 地址（排除未连接的接口）。
     * </p>
     */
    private List<String> getActiveNetworkServices() throws Exception {
        List<String> allServices = new ArrayList<>();
        String output = execAndCapture("networksetup", "-listallnetworkservices");
        for (String line : output.split("\n")) {
            String trimmed = line.trim();
            // 跳过标题行和被禁用的接口（以*开头）
            if (trimmed.isEmpty() || trimmed.startsWith("An asterisk") || trimmed.startsWith("*")) {
                continue;
            }
            allServices.add(trimmed);
        }

        // 过滤出有 IP 地址的活跃接口
        List<String> activeServices = new ArrayList<>();
        for (String service : allServices) {
            String info = execAndCapture("networksetup", "-getinfo", service);
            // 如果有非 "none" 的 IP 地址，认为是活跃的
            if (info.contains("IP address:") && !info.contains("IP address: none")) {
                activeServices.add(service);
            } else if (info.contains("Router:") && !info.contains("Router: none")) {
                // 备选判断：有路由器地址也算活跃
                activeServices.add(service);
            }
        }

        // 如果过滤后没有结果，至少尝试 Wi-Fi 和 Ethernet
        if (activeServices.isEmpty()) {
            for (String service : allServices) {
                if (service.equalsIgnoreCase("Wi-Fi") || service.equalsIgnoreCase("Ethernet")) {
                    activeServices.add(service);
                }
            }
        }

        return activeServices;
    }

    /**
     * 保存指定网络接口的当前代理状态
     */
    private ProxyState saveCurrentState(String service) throws Exception {
        ProxyState state = new ProxyState();
        state.service = service;

        // 保存 HTTP 代理状态
        String httpInfo = execAndCapture("networksetup", "-getwebproxy", service);
        state.httpEnabled = httpInfo.contains("Enabled: Yes");
        state.httpHost = extractValue(httpInfo, "Server:");
        state.httpPort = extractValue(httpInfo, "Port:");

        // 保存 HTTPS 代理状态
        String httpsInfo = execAndCapture("networksetup", "-getsecurewebproxy", service);
        state.httpsEnabled = httpsInfo.contains("Enabled: Yes");
        state.httpsHost = extractValue(httpsInfo, "Server:");
        state.httpsPort = extractValue(httpsInfo, "Port:");

        // 保存 SOCKS 代理状态
        String socksInfo = execAndCapture("networksetup", "-getsocksfirewallproxy", service);
        state.socksEnabled = socksInfo.contains("Enabled: Yes");
        state.socksHost = extractValue(socksInfo, "Server:");
        state.socksPort = extractValue(socksInfo, "Port:");

        log.debug("Saved proxy state for [{}]: http={}, https={}, socks={}",
                service, state.httpEnabled, state.httpsEnabled, state.socksEnabled);

        return state;
    }

    /**
     * 还原指定网络接口的代理状态
     */
    private void restoreState(ProxyState state) throws Exception {
        // 还原 HTTP 代理
        if (state.httpEnabled) {
            exec("networksetup", "-setwebproxy", state.service,
                    state.httpHost, state.httpPort);
            exec("networksetup", "-setwebproxystate", state.service, "on");
        } else {
            exec("networksetup", "-setwebproxystate", state.service, "off");
        }

        // 还原 HTTPS 代理
        if (state.httpsEnabled) {
            exec("networksetup", "-setsecurewebproxy", state.service,
                    state.httpsHost, state.httpsPort);
            exec("networksetup", "-setsecurewebproxystate", state.service, "on");
        } else {
            exec("networksetup", "-setsecurewebproxystate", state.service, "off");
        }

        // 还原 SOCKS 代理
        if (state.socksEnabled) {
            exec("networksetup", "-setsocksfirewallproxy", state.service,
                    state.socksHost, state.socksPort);
            exec("networksetup", "-setsocksfirewallproxystate", state.service, "on");
        } else {
            exec("networksetup", "-setsocksfirewallproxystate", state.service, "off");
        }
    }

    /**
     * 从 networksetup 输出中提取某个字段的值
     */
    private String extractValue(String output, String key) {
        for (String line : output.split("\n")) {
            if (line.trim().startsWith(key)) {
                return line.substring(line.indexOf(":") + 1).trim();
            }
        }
        return "";
    }

    /**
     * 执行命令（不关心输出）
     */
    private void exec(String... cmd) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process process = pb.start();
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            String output = readProcessOutput(process);
            throw new SystemProxyException(
                    "Command failed (exit=" + exitCode + "): " + String.join(" ", cmd) + "\n" + output);
        }
    }

    /**
     * 执行命令并捕获输出
     */
    private String execAndCapture(String... cmd) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process process = pb.start();
        String output = readProcessOutput(process);
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new SystemProxyException(
                    "Command failed (exit=" + exitCode + "): " + String.join(" ", cmd) + "\n" + output);
        }
        return output;
    }

    private String readProcessOutput(Process process) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }
        }
        return sb.toString();
    }

    /**
     * 保存一个网络接口的代理原始状态
     */
    private static class ProxyState {
        String service;
        boolean httpEnabled;
        String httpHost;
        String httpPort;
        boolean httpsEnabled;
        String httpsHost;
        String httpsPort;
        boolean socksEnabled;
        String socksHost;
        String socksPort;
    }
}
