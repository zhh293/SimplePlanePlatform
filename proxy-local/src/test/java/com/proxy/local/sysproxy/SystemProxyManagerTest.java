package com.proxy.local.sysproxy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 系统代理管理器测试
 * <p>
 * 注意：enable/disable 测试会实际修改系统代理设置，
 * 仅在本地开发环境手动运行，CI 环境跳过。
 * </p>
 */
class SystemProxyManagerTest {

    @Test
    void testCreateByPlatform() {
        SystemProxyManager manager = SystemProxyManager.create();
        assertNotNull(manager);

        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("mac")) {
            assertInstanceOf(MacSystemProxyManager.class, manager);
        } else if (os.contains("win")) {
            assertInstanceOf(WindowsSystemProxyManager.class, manager);
        } else if (os.contains("linux")) {
            assertInstanceOf(LinuxSystemProxyManager.class, manager);
        }

        System.out.println("Platform detected: " + os + " → " + manager.getClass().getSimpleName());
    }

    @Test
    void testNoopManager() {
        NoopSystemProxyManager noop = new NoopSystemProxyManager();
        assertFalse(noop.isEnabled());

        noop.enable("127.0.0.1", 1080);
        assertFalse(noop.isEnabled()); // Noop 永远返回 false

        noop.disable();
        assertFalse(noop.isEnabled());
    }

    /**
     * 实际设置系统代理并还原 —— 手动运行
     * <p>
     * 运行前请确保：
     * 1. 在 macOS 上以当前用户运行
     * 2. 运行后检查 系统偏好设置 → 网络 → 代理 验证效果
     * 3. 设置环境变量 PROXY_TEST_SYSPROXY=true 启用此测试
     * </p>
     */
    @Test
    @EnabledIfEnvironmentVariable(named = "PROXY_TEST_SYSPROXY", matches = "true")
    void testEnableAndDisable() {
        // 只在 macOS 上运行此测试
        String os = System.getProperty("os.name", "").toLowerCase();
        if (!os.contains("mac")) {
            System.out.println("Skipping real proxy test on non-macOS platform");
            return;
        }

        SystemProxyManager manager = SystemProxyManager.create();
        assertFalse(manager.isEnabled());

        // 设置系统代理
        manager.enable("127.0.0.1", 1080);
        assertTrue(manager.isEnabled());

        System.out.println("System proxy enabled - check System Preferences > Network > Proxies");

        // 还原系统代理
        manager.disable();
        assertFalse(manager.isEnabled());

        System.out.println("System proxy restored to original state");
    }

    /**
     * 测试重复 enable 不会报错
     */
    @Test
    @EnabledIfEnvironmentVariable(named = "PROXY_TEST_SYSPROXY", matches = "true")
    void testDoubleEnable() {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (!os.contains("mac")) {
            return;
        }

        SystemProxyManager manager = SystemProxyManager.create();

        manager.enable("127.0.0.1", 1080);
        assertTrue(manager.isEnabled());

        // 重复 enable 应该跳过
        manager.enable("127.0.0.1", 1080);
        assertTrue(manager.isEnabled());

        manager.disable();
        assertFalse(manager.isEnabled());
    }

    /**
     * 测试未 enable 时 disable 不会报错
     */
    @Test
    void testDisableWithoutEnable() {
        SystemProxyManager manager = SystemProxyManager.create();
        assertFalse(manager.isEnabled());

        // 不应该抛异常
        manager.disable();
        assertFalse(manager.isEnabled());
    }
}
