package com.proxy.local.config;

import com.proxy.cluster.ClientInvoker;
import com.proxy.cluster.StickyClusterInvoker;
import com.proxy.common.cluster.ClusterInvoker;
import com.proxy.common.cluster.LoadBalance;
import com.proxy.common.exchange.ExchangeClient;
import com.proxy.common.exchange.Exchanger;
import com.proxy.common.filter.*;
import com.proxy.common.model.ProxyMessage;
import com.proxy.common.model.URL;
import com.proxy.common.spi.ExtensionLoader;
import com.proxy.local.handler.StreamChannelRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.LongSupplier;

/** Owns clients; business traffic goes directly to the cluster, control work is serialized. */
public final class Http3NodeController implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(Http3NodeController.class);
    interface Connector { ExchangeClient connect(ProxyConfig.RemoteServer node); }
    private static final class Node {
        final ProxyConfig.RemoteServer config;
        final NodeCircuit circuit = new NodeCircuit();
        ClientInvoker raw;
        Invoker wrapped;
        boolean connecting, checkRequested;
        Node(ProxyConfig.RemoteServer config) { this.config = config; }
    }
    private final StickyClusterInvoker cluster = new StickyClusterInvoker();
    private final Map<String, Node> nodes = new LinkedHashMap<>();
    private final ScheduledExecutorService control = Executors.newSingleThreadScheduledExecutor(r -> daemon(r, "node-control"));
    private final ExecutorService connections = Executors.newFixedThreadPool(2, r -> daemon(r, "node-connect"));
    private final Connector connector;
    private final LongSupplier clock;
    private volatile ProxyConfig snapshot;
    private volatile String currentNodeId = "", nodeStatus = "connecting";
    private volatile boolean closed;
    private volatile String nodeStates = "[]";
    private Node checking;
    private volatile CompletableFuture<Void> manualReady = CompletableFuture.completedFuture(null);
    private long lastSelectionAt = Long.MIN_VALUE / 2;

    public Http3NodeController(ProxyConfig config) {
        this(config, Http3NodeController::connect, () -> TimeUnit.NANOSECONDS.toMillis(System.nanoTime()));
    }

    Http3NodeController(ProxyConfig config, Connector connector, LongSupplier clock) {
        config.validate();
        this.connector = connector;
        this.clock = clock;
        snapshot = config;
        cluster.setLoadBalance(ExtensionLoader.getLoader(LoadBalance.class).getExtension("random"));
        cluster.setCurrentInvoker(null);
        runControl(() -> initialize(config));
        if (!automatic()) awaitManualReady();
        control.scheduleWithFixedDelay(this::tickSafely, 250, 250, TimeUnit.MILLISECONDS);
    }

    private static Thread daemon(Runnable action, String name) {
        Thread thread = new Thread(action, name);
        thread.setDaemon(true);
        return thread;
    }

    private static ExchangeClient connect(ProxyConfig.RemoteServer node) {
        URL url = new URL("proxy", node.getHost(), node.getPort())
                .addParameter("transport", "http3").addParameter("ssl", String.valueOf(node.isSsl()))
                .addParameter("cipher", node.getCipher()).addParameter("cipherKey", node.getCipherKey());
        node.getHttp3().toUrlParameters(url);
        return ExtensionLoader.getLoader(Exchanger.class).getDefaultExtension().connect(url);
    }

    private boolean automatic() { return "auto".equals(snapshot.getNodeSelection().getMode()); }
    public ClusterInvoker getClusterInvoker() { return cluster; }
    public String getCurrentNodeId() { return currentNodeId; }
    public int getBindingCount() { return cluster.getBindingCount(); }
    public String getMode() { return snapshot.getNodeSelection().getMode(); }
    public String getPreferredNodeId() {
        String preferred = snapshot.getNodeSelection().getPreferredNodeId();
        return preferred == null ? "" : preferred;
    }
    public String getNodeStatesJson() { return nodeStates; }
    public String getNodeStatus() {
        if (closed) return "stopped";
        return "available".equals(nodeStatus) && !cluster.isAvailable() ? "unavailable" : nodeStatus;
    }

    public void apply(ProxyConfig next) {
        next.validate();
        runControl(() -> {
            if (closed) throw new IllegalStateException("Node controller closed");
            if (automatic() && "auto".equals(next.getNodeSelection().getMode())) {
                if (!new org.yaml.snakeyaml.Yaml().dump(snapshot).equals(new org.yaml.snakeyaml.Yaml().dump(next)))
                    throw new IllegalArgumentException("Automatic node configuration changes require restart");
                return;
            }
            if (getMode().equals(next.getNodeSelection().getMode()) && selectionKey(snapshot).equals(selectionKey(next))) {
                snapshot = next;
                return;
            }
            retireCurrent();
            for (Node node : nodes.values()) closeNode(node);
            nodes.clear();
            checking = null;
            snapshot = next;
            initialize(next);
        });
    }

    private void awaitManualReady() {
        try { manualReady.get(12000, TimeUnit.MILLISECONDS); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IllegalStateException(error); }
        catch (ExecutionException | TimeoutException error) { throw new IllegalStateException("Manual node initialization did not finish", error); }
    }

    private static List<Object> selectionKey(ProxyConfig config) {
        ProxyConfig.RemoteServer node = config.getRemoteServers().stream()
                .filter(n -> n.getId().equals(config.getNodeSelection().getSelectedNodeId())).findFirst().get();
        return Arrays.asList(node.getId(), node.getHost(), node.getPort(), node.getTransport(), node.isSsl(),
                node.getCipher(), node.getCipherKey(), new org.yaml.snakeyaml.Yaml().dump(node.getHttp3()), config.getTimeoutMs());
    }

    private void initialize(ProxyConfig config) {
        manualReady = new CompletableFuture<>();
        // A new mode/configuration starts a fresh selection epoch, including preferred-node waiting.
        lastSelectionAt = Long.MIN_VALUE / 2;
        currentNodeId = automatic() ? "" : config.getNodeSelection().getSelectedNodeId();
        nodeStatus = "connecting";
        cluster.setCurrentInvoker(null);
        cluster.setInvokers(Collections.emptyList());
        for (ProxyConfig.RemoteServer remote : config.getRemoteServers()) {
            if (automatic() || remote.getId().equals(currentNodeId)) {
                Node node = new Node(remote);
                nodes.put(remote.getId(), node);
                beginConnect(node);
            }
        }
        publishStates();
    }

    private void beginConnect(Node node) {
        if (node.connecting || closed) return;
        node.connecting = true;
        connections.execute(() -> {
            ExchangeClient client = null;
            RuntimeException failure = null;
            try { client = connector.connect(node.config); }
            catch (RuntimeException error) { failure = error; }
            final ExchangeClient result = client;
            final RuntimeException error = failure;
            try {
                control.execute(() -> {
                    if (closed || nodes.get(node.config.getId()) != node) {
                        if (result != null) result.close();
                        return;
                    }
                    node.connecting = false;
                    if (error != null || result == null) {
                        node.circuit.trip(clock.getAsLong(), "Connection failed", jitter());
                        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
                            if (cause instanceof java.security.cert.CertificateException
                                    || cause instanceof java.security.cert.CertPathValidatorException
                                    || cause instanceof javax.net.ssl.SSLPeerUnverifiedException) {
                                node.circuit.blocked = true;
                                node.circuit.reason = "Certificate configuration invalid; restart after correcting it";
                            }
                        }
                        log.warn("Cannot connect to node {}", node.config.getId(), error);
                        if (!automatic()) nodeStatus = "unavailable";
                    } else {
                        result.setStreamRegistry(StreamChannelRegistry.getInstance().getInternalMap());
                        result.setStreamClosedListener(cluster::unbind);
                        node.raw = new ClientInvoker(result, snapshot.getTimeoutMs(), node.config.getId());
                        FilterChainBuilder builder = ExtensionLoader.getLoader(FilterChainBuilder.class).getDefaultExtension();
                        node.wrapped = builder.build(new Invoker() {
                            public CompletableFuture<Response> invoke(Invocation invocation) { return observe(node, invocation); }
                            public boolean isAvailable() { return node.raw != null && node.raw.isAvailable(); }
                            public int getActiveCount() { return node.raw == null ? 0 : node.raw.getActiveCount(); }
                        }, ExtensionLoader.getLoader(Filter.class).getActivateExtensions("client"));
                        refreshMembers();
                        if (!automatic()) {
                            cluster.setCurrentInvoker(node.wrapped);
                            node.circuit.activate(clock.getAsLong());
                            nodeStatus = "available";
                        } else chooseCurrent();
                    }
                    publishStates();
                    if (!automatic()) manualReady.complete(null);
                });
            } catch (RejectedExecutionException stopped) { if (result != null) result.close(); }
        });
    }

    private CompletableFuture<Response> observe(Node node, Invocation invocation) {
        ClientInvoker client = node.raw;
        return client.invoke(invocation).whenComplete((response, error) -> {
            if (invocation.getType() != ProxyMessage.MessageType.CONNECT) return;
            dispatch(() -> {
                if (closed || !automatic() || node.raw != client || nodes.get(node.config.getId()) != node) return;
                if (remoteResponse(response)) {
                    node.circuit.response(clock.getAsLong());
                    node.checkRequested = false;
                } else node.checkRequested = true;
            });
        });
    }

    private static boolean remoteResponse(Response response) {
        return response != null && Boolean.TRUE.equals(response.getAttachments().get("remoteResponse"));
    }

    private void refreshMembers() {
        List<Invoker> members = new ArrayList<>();
        for (Node node : nodes.values()) if (node.wrapped != null) members.add(node.wrapped);
        cluster.setInvokers(members);
    }

    private void chooseCurrent() {
        if (!automatic() || !currentNodeId.isEmpty()) return;
        long now = clock.getAsLong();
        if (now - lastSelectionAt < 5000) return;
        Node preferred = nodes.get(getPreferredNodeId());
        if (preferred != null && preferred.connecting && lastSelectionAt < 0) return;
        List<Invoker> eligible = new ArrayList<>();
        for (Node node : nodes.values()) if (node.circuit.eligible() && node.wrapped != null) eligible.add(node.wrapped);
        Invoker selected = cluster.selectCurrent(eligible, preferred != null && preferred.circuit.eligible() ? preferred.wrapped : null);
        if (selected == null) { nodeStatus = "unavailable"; return; }
        for (Node node : nodes.values()) if (node.wrapped == selected) {
            currentNodeId = node.config.getId();
            node.circuit.activate(now);
            node.checkRequested = false;
            nodeStatus = "available";
            lastSelectionAt = now;
            log.info("Current automatic node: {}", currentNodeId);
            return;
        }
    }

    private void tickSafely() {
        try { tick(); } catch (RuntimeException error) { log.warn("Node health check failed", error); }
    }

    void tickForTest() { runControl(this::tick); }

    private void tick() {
        if (closed || !automatic()) return;
        long now = clock.getAsLong();
        for (Node node : nodes.values()) {
            if (node.circuit.state != NodeCircuit.State.OPEN && node.raw != null
                    && node.circuit.disconnected(now, node.raw.isAvailable())) fail(node, "Connection unavailable");
            if (checking == null && node.circuit.beginRecovery(now)) beginConnect(node);
            if (checking == null && node.raw != null && node.raw.isAvailable() && now >= node.circuit.nextCheckAt
                    && (node.circuit.state == NodeCircuit.State.HALF_OPEN || node.checkRequested)) probe(node);
        }
        Node active = nodes.get(currentNodeId);
        if (active != null && active.circuit.observedResponse && now - active.circuit.activatedAt >= NodeCircuit.STABLE_MS)
            active.circuit.openings = 0;
        chooseCurrent();
        publishStates();
    }

    private void probe(Node node) {
        checking = node;
        ClientInvoker client = node.raw;
        long streamId = StreamChannelRegistry.getInstance().nextStreamId();
        ProxyMessage connect = new ProxyMessage();
        connect.setType(ProxyMessage.MessageType.CONNECT);
        connect.setStreamId(streamId);
        connect.setHost(snapshot.getNodeSelection().getProbeHost());
        connect.setPort(snapshot.getNodeSelection().getProbePort());
        CompletableFuture<Response> check;
        try { check = client.getExchangeClient().request(connect, Math.max(6000, snapshot.getTimeoutMs())); }
        catch (RuntimeException failure) {
            check = new CompletableFuture<>();
            check.completeExceptionally(failure);
        }
        check.whenComplete((response, error) -> {
            ProxyMessage disconnect = new ProxyMessage();
            disconnect.setType(ProxyMessage.MessageType.DISCONNECT);
            disconnect.setStreamId(streamId);
            disconnect.setHost(connect.getHost());
            disconnect.setPort(connect.getPort());
            CompletableFuture<Response> cleanup;
            try { cleanup = client.getExchangeClient().request(disconnect, 2000); }
            catch (RuntimeException failure) { cleanup = CompletableFuture.completedFuture(null); }
            cleanup.whenComplete((ignored, cleanupError) -> dispatch(() -> {
                if (checking == node) checking = null;
                if (closed || !automatic() || nodes.get(node.config.getId()) != node || node.raw != client) return;
                long now = clock.getAsLong();
                if (remoteResponse(response)) {
                    if (node.circuit.state == NodeCircuit.State.HALF_OPEN) node.circuit.recoveredResponse(now);
                    else { node.circuit.response(now); node.checkRequested = false; }
                } else if (node.circuit.state == NodeCircuit.State.HALF_OPEN || node.circuit.failedCheck(now)) {
                    fail(node, "Health check failed");
                }
                publishStates();
            }));
        });
    }

    private void fail(Node node, String reason) {
        node.circuit.trip(clock.getAsLong(), reason, jitter());
        if (node.config.getId().equals(currentNodeId)) {
            retireCurrent();
            lastSelectionAt = clock.getAsLong() - 5000;
        } else closeNode(node);
        refreshMembers();
    }

    private static double jitter() { return ThreadLocalRandom.current().nextDouble(0.2); }

    private void retireCurrent() {
        cluster.setCurrentInvoker(null);
        try { cluster.retireBindings().get(Math.min(2000, snapshot.getTimeoutMs()), TimeUnit.MILLISECONDS); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        catch (ExecutionException | TimeoutException ignored) { log.debug("Closing old node without disconnect acknowledgement"); }
        Node old = nodes.get(currentNodeId);
        if (old != null) closeNode(old);
        currentNodeId = "";
    }

    private void closeNode(Node node) {
        if (node.raw != null) node.raw.close();
        node.raw = null;
        // Keep the closed member represented until its replacement is connected (or the mode changes).
        node.checkRequested = false;
        if (checking == node) checking = null;
    }

    private void publishStates() {
        StringBuilder json = new StringBuilder("[");
        for (Node node : nodes.values()) {
            if (json.length() > 1) json.append(',');
            json.append("{\"nodeId\":\"").append(escape(node.config.getId())).append("\",\"circuit\":\"")
                    .append(node.circuit.state).append("\",\"connecting\":").append(node.connecting)
                    .append(",\"available\":").append(node.raw != null && node.raw.isAvailable())
                    .append(",\"retryAfterMs\":").append(Math.max(0, node.circuit.openUntil - clock.getAsLong()))
                    .append(",\"reason\":\"").append(escape(node.circuit.reason)).append("\"}");
        }
        nodeStates = json.append(']').toString();
    }

    private static String escape(String text) {
        StringBuilder json = new StringBuilder();
        for (char c : text.toCharArray()) {
            if (c == '"' || c == '\\') json.append('\\').append(c);
            else if (c < 32) json.append(String.format("\\u%04x", (int) c));
            else json.append(c);
        }
        return json.toString();
    }
    private void dispatch(Runnable action) {
        try { control.execute(action); } catch (RejectedExecutionException ignored) { }
    }
    private void runControl(Runnable action) {
        try { control.submit(action).get(); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IllegalStateException(error); }
        catch (ExecutionException error) {
            if (error.getCause() instanceof RuntimeException) throw (RuntimeException) error.getCause();
            throw new IllegalStateException(error.getCause());
        }
    }

    @Override public synchronized void close() {
        if (closed) return;
        runControl(() -> {
            if (closed) return;
            closed = true;
            retireCurrent();
            for (Node node : nodes.values()) closeNode(node);
            nodes.clear();
            cluster.setInvokers(Collections.emptyList());
            nodeStatus = "stopped";
        });
        connections.shutdownNow();
        control.shutdown();
    }
}
