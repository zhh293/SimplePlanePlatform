package com.proxy.local.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Digest polling survives atomic file replacement and coalesces duplicate notifications. */
public final class NodeConfigWatcher implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(NodeConfigWatcher.class);
    private final Path configPath;
    private final Http3NodeController controller;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "node-config-watcher");
        thread.setDaemon(true);
        return thread;
    });
    private final String instanceId = UUID.randomUUID().toString();
    private final java.util.List<Object> startupSettings;
    private final java.util.concurrent.atomic.AtomicBoolean closed = new java.util.concurrent.atomic.AtomicBoolean();
    private String observedDigest;
    private String appliedDigest;
    private String error = "";

    public NodeConfigWatcher(Path path, Http3NodeController controller, ProxyConfig startupConfig) throws Exception {
        this.configPath = path.toAbsolutePath();
        this.controller = controller;
        byte[] bytes = Files.readAllBytes(configPath);
        startupSettings = nonNodeSettings(startupConfig);
        ProxyConfig initial = new Yaml().loadAs(new String(bytes, StandardCharsets.UTF_8), ProxyConfig.class);
        initial.validate();
        if (!startupSettings.equals(nonNodeSettings(initial))) {
            throw new IllegalArgumentException("Non-node settings changed during startup; restart required");
        }
        controller.apply(initial);
        observedDigest = appliedDigest = digest(bytes);
        publish(true);
        scheduler.scheduleWithFixedDelay(this::poll, 500, 500, TimeUnit.MILLISECONDS);
    }

    private void poll() {
        if (closed.get()) return;
        try {
            byte[] bytes = Files.readAllBytes(configPath);
            String nextDigest = digest(bytes);
            if (!nextDigest.equals(observedDigest)) {
                observedDigest = nextDigest;
                try {
                    ProxyConfig next = new Yaml().loadAs(new String(bytes, StandardCharsets.UTF_8), ProxyConfig.class);
                    if (next == null) {
                        throw new IllegalArgumentException("nodeSelection is required");
                    }
                    next.validate();
                    if (next.getNodeSelection() == null) throw new IllegalArgumentException("nodeSelection is required");
                    if (!startupSettings.equals(nonNodeSettings(next))) {
                        throw new IllegalArgumentException("Non-node configuration changes require restart");
                    }
                    controller.apply(next);
                    appliedDigest = nextDigest;
                    error = "";
                } catch (Exception failure) {
                    error = "Configuration not applied; check local proxy log";
                    log.warn("Node configuration rejected: {}", failure.getClass().getSimpleName());
                }
            }
            publish(true);
        } catch (Exception failure) {
            log.warn("Cannot read/publish node configuration status: {}", failure.getClass().getSimpleName());
        }
    }

    private static java.util.List<Object> nonNodeSettings(ProxyConfig config) {
        return java.util.Arrays.asList(config.getLocalPort(), config.isHttpProxyEnabled(),
                config.getSystemProxy().isEnabled(), config.getSystemProxy().getHost(), new Yaml().dump(config.getRoute()));
    }

    private synchronized void publish(boolean running) throws Exception {
        if (running && closed.get()) return;
        String json = "{\"instanceId\":\"" + instanceId + "\",\"running\":" + running
                + ",\"updatedAt\":" + System.currentTimeMillis()
                + ",\"savedRevision\":\"" + observedDigest + "\",\"appliedRevision\":\"" + appliedDigest
                + "\",\"currentNodeId\":\"" + escape(controller.getCurrentNodeId())
                + "\",\"activeStreams\":" + controller.getActiveStreamCount()
                + ",\"mode\":\"" + escape(controller.getMode()) + "\""
                + ",\"preferredNodeId\":\"" + escape(controller.getPreferredNodeId()) + "\""
                + ",\"nodes\":" + controller.getNodeStatesJson()
                + ",\"nodeStatus\":\"" + controller.getNodeStatus() + "\""
                + ",\"error\":\"" + error + "\"}\n";
        Path target = configPath.resolveSibling(configPath.getFileName() + ".status.json");
        Path temp = Files.createTempFile(target.getParent(), ".node-status-", ".tmp");
        try {
            Files.write(temp, json.getBytes(StandardCharsets.UTF_8));
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temp); }
    }

    private static String escape(String value) {
        StringBuilder result = new StringBuilder();
        for (char c : value.toCharArray()) {
            if (c == '"' || c == '\\') result.append('\\').append(c);
            else if (c < 32) result.append(String.format("\\u%04x", (int) c));
            else result.append(c);
        }
        return result.toString();
    }

    private static String digest(byte[] bytes) throws Exception {
        StringBuilder result = new StringBuilder();
        for (byte b : MessageDigest.getInstance("SHA-256").digest(bytes)) result.append(String.format("%02x", b));
        return result.toString();
    }

    @Override public void close() {
        closed.set(true);
        scheduler.shutdownNow();
        try { publish(false); } catch (Exception failure) { log.debug("Cannot publish stopped state", failure); }
    }
}
