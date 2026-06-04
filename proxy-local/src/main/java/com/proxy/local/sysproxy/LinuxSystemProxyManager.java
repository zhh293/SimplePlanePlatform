package com.proxy.local.sysproxy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStreamReader;

/**
 * Linux 系统代理管理
 * <p>
 * 支持 GNOME 桌面环境（通过 gsettings 命令）。
 * 对于无桌面环境的 Linux，代理需要通过环境变量设置，
 * 但环境变量无法在运行时修改其他进程，故仅支持 GNOME。
 * </p>
 * <p>
 * GNOME 代理设置路径：
 * - org.gnome.system.proxy mode (none/manual/auto)
 * - org.gnome.system.proxy.http host/port
 * - org.gnome.system.proxy.https host/port
 * - org.gnome.system.proxy.socks host/port
 * </p>
 */
public class LinuxSystemProxyManager implements SystemProxyManager {

    private static final Logger log = LoggerFactory.getLogger(LinuxSystemProxyManager.class);

    private volatile boolean enabled = false;
    private boolean gnomeAvailable = false;

    // 保存原始状态
    private String originalMode = "none";
    private String originalHttpHost = "";
    private int originalHttpPort = 0;
    private String originalHttpsHost = "";
    private int originalHttpsPort = 0;
    private String originalSocksHost = "";
    private int originalSocksPort = 0;

    @Override
    public void enable(String host, int port) throws SystemProxyException {
        if (enabled) {
            log.warn("System proxy already enabled, skipping");
            return;
        }

        // 检测是否有 gsettings（GNOME 桌面环境）
        gnomeAvailable = isGsettingsAvailable();
        if (!gnomeAvailable) {
            log.warn("gsettings not available - GNOME desktop not detected. " +
                    "System proxy cannot be set automatically on this Linux environment. " +
                    "Please set http_proxy/https_proxy environment variables manually.");
            return;
        }

        try {
            // 1. 保存原始状态
            originalMode = gsettingsGet("org.gnome.system.proxy", "mode");
            originalHttpHost = gsettingsGet("org.gnome.system.proxy.http", "host");
            originalHttpPort = parsePort(gsettingsGet("org.gnome.system.proxy.http", "port"));
            originalHttpsHost = gsettingsGet("org.gnome.system.proxy.https", "host");
            originalHttpsPort = parsePort(gsettingsGet("org.gnome.system.proxy.https", "port"));
            originalSocksHost = gsettingsGet("org.gnome.system.proxy.socks", "host");
            originalSocksPort = parsePort(gsettingsGet("org.gnome.system.proxy.socks", "port"));

            log.info("Saved original GNOME proxy state: mode={}", originalMode);

            // 2. 设置代理
            gsettingsSet("org.gnome.system.proxy", "mode", "'manual'");
            gsettingsSet("org.gnome.system.proxy.http", "host", "'" + host + "'");
            gsettingsSet("org.gnome.system.proxy.http", "port", String.valueOf(port));
            gsettingsSet("org.gnome.system.proxy.https", "host", "'" + host + "'");
            gsettingsSet("org.gnome.system.proxy.https", "port", String.valueOf(port));
            gsettingsSet("org.gnome.system.proxy.socks", "host", "'" + host + "'");
            gsettingsSet("org.gnome.system.proxy.socks", "port", String.valueOf(port));

            enabled = true;
            log.info("Linux (GNOME) system proxy enabled: {}:{}", host, port);

        } catch (SystemProxyException e) {
            throw e;
        } catch (Exception e) {
            throw new SystemProxyException("Failed to enable Linux system proxy", e);
        }
    }

    @Override
    public void disable() throws SystemProxyException {
        if (!enabled || !gnomeAvailable) {
            return;
        }

        try {
            // 还原原始状态
            gsettingsSet("org.gnome.system.proxy", "mode", "'" + originalMode + "'");
            gsettingsSet("org.gnome.system.proxy.http", "host", "'" + originalHttpHost + "'");
            gsettingsSet("org.gnome.system.proxy.http", "port", String.valueOf(originalHttpPort));
            gsettingsSet("org.gnome.system.proxy.https", "host", "'" + originalHttpsHost + "'");
            gsettingsSet("org.gnome.system.proxy.https", "port", String.valueOf(originalHttpsPort));
            gsettingsSet("org.gnome.system.proxy.socks", "host", "'" + originalSocksHost + "'");
            gsettingsSet("org.gnome.system.proxy.socks", "port", String.valueOf(originalSocksPort));

            enabled = false;
            log.info("Linux (GNOME) system proxy restored to original state (mode={})", originalMode);

        } catch (Exception e) {
            throw new SystemProxyException("Failed to disable Linux system proxy", e);
        }
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    private boolean isGsettingsAvailable() {
        try {
            ProcessBuilder pb = new ProcessBuilder("which", "gsettings");
            pb.redirectErrorStream(true);
            Process process = pb.start();
            return process.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private String gsettingsGet(String schema, String key) throws Exception {
        String output = execAndCapture("gsettings", "get", schema, key);
        // gsettings 返回值带引号，如 'none' → 去掉引号
        return output.trim().replace("'", "");
    }

    private void gsettingsSet(String schema, String key, String value) throws Exception {
        exec("gsettings", "set", schema, key, value);
    }

    private int parsePort(String portStr) {
        try {
            return Integer.parseInt(portStr.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

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
}
