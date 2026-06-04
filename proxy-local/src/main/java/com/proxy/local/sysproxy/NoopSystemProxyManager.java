package com.proxy.local.sysproxy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 空实现 —— 用于不支持的平台或用户主动禁用系统代理设置时
 */
public class NoopSystemProxyManager implements SystemProxyManager {

    private static final Logger log = LoggerFactory.getLogger(NoopSystemProxyManager.class);

    @Override
    public void enable(String host, int port) {
        log.info("System proxy auto-configuration is disabled or not supported on this platform");
    }

    @Override
    public void disable() {
        // no-op
    }

    @Override
    public boolean isEnabled() {
        return false;
    }
}
