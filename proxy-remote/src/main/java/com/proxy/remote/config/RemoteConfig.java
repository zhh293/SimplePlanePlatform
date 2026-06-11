package com.proxy.remote.config;

import com.proxy.common.model.URL;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.Collections;
import java.util.Map;

/**
 * 服务端 YAML 配置加载
 * <p>
 * 从 classpath 加载 remote.yml，解析为 URL 对象供各层使用。
 * 配置项不存在时使用合理的默认值。
 * </p>
 */
public class RemoteConfig {

    private static final Logger log = LoggerFactory.getLogger(RemoteConfig.class);

    private static final String CONFIG_FILE = "remote.yml";

    // 默认值
    private static final String DEFAULT_HOST = "0.0.0.0";
    private static final int DEFAULT_PORT = 9090;
    private static final int DEFAULT_BIZ_THREADS = 200;
    private static final int DEFAULT_WORKER_THREADS = 0;  // 0 表示 CPU×2
    private static final int DEFAULT_BOSS_THREADS = 1;
    private static final String DEFAULT_CIPHER = "aes-gcm";
    private static final String DEFAULT_CIPHER_KEY = "";
    private static final int DEFAULT_MAX_STREAMS = 100;
    private static final int DEFAULT_READ_IDLE_TIMEOUT = 60;
    private static final int DEFAULT_BACKLOG = 1024;
    private static final boolean DEFAULT_BACKPRESSURE = false;
    private static final int DEFAULT_BACKPRESSURE_PERMITS = 64;

    // Outbound 出站连接默认值
    private static final int DEFAULT_OUTBOUND_CONNECT_TIMEOUT = 5000;
    private static final int DEFAULT_OUTBOUND_ACTIVE_WAIT_TIMEOUT = 5000;
    private static final boolean DEFAULT_OUTBOUND_KEEP_ALIVE = true;
    private static final boolean DEFAULT_OUTBOUND_TCP_NO_DELAY = true;

    /**
     * 加载配置文件并转换为 URL 对象
     *
     * @return 包含所有配置参数的 URL
     */
    public URL loadURL() {
        Map<String, Object> config = loadYaml();

        String host = getStringValue(config, "host", DEFAULT_HOST);
        int port = getIntValue(config, "port", DEFAULT_PORT);

        URL url = new URL("proxy", host, port);

        // 线程配置
        url.addParameter("bizThreads", getIntValue(config, "bizThreads", DEFAULT_BIZ_THREADS));
        url.addParameter("workerThreads", getIntValue(config, "workerThreads", DEFAULT_WORKER_THREADS));
        url.addParameter("bossThreads", getIntValue(config, "bossThreads", DEFAULT_BOSS_THREADS));

        // 加密配置
        url.addParameter("cipher", getStringValue(config, "cipher", DEFAULT_CIPHER));
        url.addParameter("cipherKey", getStringValue(config, "cipherKey", DEFAULT_CIPHER_KEY));

        // 连接配置
        url.addParameter("maxStreams", getIntValue(config, "maxStreams", DEFAULT_MAX_STREAMS));
        url.addParameter("readIdleTimeout", getIntValue(config, "readIdleTimeout", DEFAULT_READ_IDLE_TIMEOUT));
        url.addParameter("backlog", getIntValue(config, "backlog", DEFAULT_BACKLOG));

        // 背压配置
        url.addParameter("backpressure",
                String.valueOf(getBooleanValue(config, "backpressure", DEFAULT_BACKPRESSURE)));
        url.addParameter("backpressurePermits",
                getIntValue(config, "backpressurePermits", DEFAULT_BACKPRESSURE_PERMITS));

        // Outbound 出站连接配置
        Map<String, Object> outbound = getMapValue(config, "outbound");
        url.addParameter("outbound.connectTimeoutMs",
                getIntValue(outbound, "connectTimeoutMs", DEFAULT_OUTBOUND_CONNECT_TIMEOUT));
        url.addParameter("outbound.activeWaitTimeoutMs",
                getIntValue(outbound, "activeWaitTimeoutMs", DEFAULT_OUTBOUND_ACTIVE_WAIT_TIMEOUT));
        url.addParameter("outbound.keepAlive",
                String.valueOf(getBooleanValue(outbound, "keepAlive", DEFAULT_OUTBOUND_KEEP_ALIVE)));
        url.addParameter("outbound.tcpNoDelay",
                String.valueOf(getBooleanValue(outbound, "tcpNoDelay", DEFAULT_OUTBOUND_TCP_NO_DELAY)));

        log.info("Loaded remote config: {}", url);
        return url;
    }

    /**
     * 从 classpath 加载 YAML 文件
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> loadYaml() {
        Yaml yaml = new Yaml();
        try (InputStream is = Thread.currentThread().getContextClassLoader()
                .getResourceAsStream(CONFIG_FILE)) {
            if (is == null) {
                log.warn("Config file '{}' not found in classpath, using defaults", CONFIG_FILE);
                return Collections.emptyMap();
            }
            Map<String, Object> config = yaml.load(is);
            return config != null ? config : Collections.emptyMap();
        } catch (Exception e) {
            log.error("Failed to load config file '{}', using defaults", CONFIG_FILE, e);
            return Collections.emptyMap();
        }
    }

    private String getStringValue(Map<String, Object> config, String key, String defaultValue) {
        Object value = config.get(key);
        return value != null ? value.toString() : defaultValue;
    }

    private int getIntValue(Map<String, Object> config, String key, int defaultValue) {
        Object value = config.get(key);
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        try {
            return Integer.parseInt(value.toString());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private boolean getBooleanValue(Map<String, Object> config, String key, boolean defaultValue) {
        Object value = config.get(key);
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        return Boolean.parseBoolean(value.toString());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getMapValue(Map<String, Object> config, String key) {
        Object value = config.get(key);
        if (value instanceof Map) {
            return (Map<String, Object>) value;
        }
        return Collections.emptyMap();
    }
}
