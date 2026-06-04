package com.proxy.local.sysproxy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStreamReader;

/**
 * Windows 系统代理管理
 * <p>
 * 通过修改注册表设置全局 HTTP/HTTPS 代理。
 * Windows 的 Internet 代理设置位于：
 * HKCU\Software\Microsoft\Windows\CurrentVersion\Internet Settings
 * </p>
 * <p>
 * 关键注册表项：
 * - ProxyEnable (DWORD): 0=关闭, 1=开启
 * - ProxyServer (String): 代理地址，如 "127.0.0.1:1080"
 * - ProxyOverride (String): 不走代理的地址列表
 * </p>
 */
public class WindowsSystemProxyManager implements SystemProxyManager {

    private static final Logger log = LoggerFactory.getLogger(WindowsSystemProxyManager.class);

    private static final String REG_PATH =
            "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Internet Settings";

    private volatile boolean enabled = false;

    // 保存原始状态
    private int originalProxyEnable = 0;
    private String originalProxyServer = "";

    @Override
    public void enable(String host, int port) throws SystemProxyException {
        if (enabled) {
            log.warn("System proxy already enabled, skipping");
            return;
        }

        try {
            // 1. 保存原始状态
            originalProxyEnable = queryRegDword("ProxyEnable");
            originalProxyServer = queryRegString("ProxyServer");

            log.info("Saved original proxy state: enable={}, server='{}'",
                    originalProxyEnable, originalProxyServer);

            // 2. 设置代理
            String proxyServer = host + ":" + port;
            setRegDword("ProxyEnable", 1);
            setRegString("ProxyServer", proxyServer);

            // 3. 设置 bypass 列表（本地地址不走代理）
            setRegString("ProxyOverride", "localhost;127.*;10.*;172.16.*;<local>");

            // 4. 通知系统代理设置已变更
            refreshInternetSettings();

            enabled = true;
            log.info("Windows system proxy enabled: {}", proxyServer);

        } catch (SystemProxyException e) {
            throw e;
        } catch (Exception e) {
            throw new SystemProxyException("Failed to enable Windows system proxy", e);
        }
    }

    @Override
    public void disable() throws SystemProxyException {
        if (!enabled) {
            return;
        }

        try {
            // 还原原始状态
            setRegDword("ProxyEnable", originalProxyEnable);
            if (originalProxyServer != null && !originalProxyServer.isEmpty()) {
                setRegString("ProxyServer", originalProxyServer);
            } else {
                deleteRegValue("ProxyServer");
            }

            // 通知系统刷新
            refreshInternetSettings();

            enabled = false;
            log.info("Windows system proxy restored to original state");

        } catch (Exception e) {
            throw new SystemProxyException("Failed to disable Windows system proxy", e);
        }
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    // ==================== 注册表操作 ====================

    private int queryRegDword(String valueName) throws Exception {
        String output = execAndCapture("reg", "query", REG_PATH, "/v", valueName);
        // 输出格式: "    ProxyEnable    REG_DWORD    0x00000001"
        for (String line : output.split("\n")) {
            if (line.contains(valueName) && line.contains("REG_DWORD")) {
                String hex = line.trim().split("\\s+")[2];
                return Integer.decode(hex);
            }
        }
        return 0; // 默认关闭
    }

    private String queryRegString(String valueName) throws Exception {
        try {
            String output = execAndCapture("reg", "query", REG_PATH, "/v", valueName);
            for (String line : output.split("\n")) {
                if (line.contains(valueName) && line.contains("REG_SZ")) {
                    String[] parts = line.trim().split("\\s+", 3);
                    return parts.length >= 3 ? parts[2] : "";
                }
            }
        } catch (Exception e) {
            // 键不存在时 reg query 会报错，返回空字符串
        }
        return "";
    }

    private void setRegDword(String valueName, int value) throws Exception {
        exec("reg", "add", REG_PATH, "/v", valueName, "/t", "REG_DWORD",
                "/d", String.valueOf(value), "/f");
    }

    private void setRegString(String valueName, String value) throws Exception {
        exec("reg", "add", REG_PATH, "/v", valueName, "/t", "REG_SZ",
                "/d", value, "/f");
    }

    private void deleteRegValue(String valueName) throws Exception {
        try {
            exec("reg", "delete", REG_PATH, "/v", valueName, "/f");
        } catch (Exception e) {
            // 忽略删除失败（可能本来就不存在）
            log.debug("Failed to delete registry value '{}': {}", valueName, e.getMessage());
        }
    }

    /**
     * 通知 Windows 刷新 Internet 代理设置
     * <p>
     * 通过 PowerShell 调用 InternetSetOption API 通知应用刷新设置。
     * 部分应用（如 Chrome）会在检测到注册表变化时自动刷新，
     * 但调用 API 可以确保所有应用立即生效。
     * </p>
     */
    private void refreshInternetSettings() {
        try {
            String psCommand = "[System.Runtime.InteropServices.Marshal]::Release(" +
                    "[System.Runtime.InteropServices.Marshal]::StringToHGlobalAnsi('')) | Out-Null; " +
                    "$signature = @'" + "\n" +
                    "[DllImport(\"wininet.dll\", SetLastError = true)]" + "\n" +
                    "public static extern bool InternetSetOption(IntPtr hInternet, int dwOption, IntPtr lpBuffer, int dwBufferLength);" + "\n" +
                    "'@" + "\n" +
                    "$type = Add-Type -MemberDefinition $signature -Name WinInet -Namespace Proxy -PassThru; " +
                    "$type::InternetSetOption([IntPtr]::Zero, 39, [IntPtr]::Zero, 0); " +
                    "$type::InternetSetOption([IntPtr]::Zero, 37, [IntPtr]::Zero, 0)";
            exec("powershell", "-NoProfile", "-Command", psCommand);
        } catch (Exception e) {
            // 刷新失败不影响主流程，大部分应用会自动检测注册表变化
            log.warn("Failed to refresh Internet settings (non-critical): {}", e.getMessage());
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
