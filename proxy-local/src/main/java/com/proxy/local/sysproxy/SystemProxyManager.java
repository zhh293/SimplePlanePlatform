package com.proxy.local.sysproxy;

/**
 * 系统代理管理接口
 * <p>
 * 负责在本地代理启动时自动设置操作系统的全局代理，
 * 关闭时还原到原始设置。
 * </p>
 */
public interface SystemProxyManager {

    /**
     * 启用系统代理，将系统的 HTTP/HTTPS/SOCKS 代理指向本地代理端口
     *
     * @param host 代理监听地址（通常为 127.0.0.1）
     * @param port 代理监听端口
     * @throws SystemProxyException 设置失败时抛出
     */
    void enable(String host, int port) throws SystemProxyException;

    /**
     * 还原系统代理到启用之前的状态
     *
     * @throws SystemProxyException 还原失败时抛出
     */
    void disable() throws SystemProxyException;

    /**
     * 当前系统代理是否已被本程序设置
     */
    boolean isEnabled();

    /**
     * 根据当前操作系统创建对应的 SystemProxyManager 实例
     */
    static SystemProxyManager create() {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("mac") || os.contains("darwin")) {
            return new MacSystemProxyManager();
        } else if (os.contains("win")) {
            return new WindowsSystemProxyManager();
        } else if (os.contains("linux") || os.contains("nix") || os.contains("nux")) {
            return new LinuxSystemProxyManager();
        } else {
            return new NoopSystemProxyManager();
        }
    }
}
