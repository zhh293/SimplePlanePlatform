package com.proxy.local.config;

import com.proxy.cluster.StickyClusterInvoker;
import com.proxy.common.exchange.ExchangeClient;
import com.proxy.common.filter.Invocation;
import com.proxy.common.filter.Response;
import com.proxy.common.model.ProxyMessage;
import com.proxy.common.transport.Client;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import static org.junit.jupiter.api.Assertions.*;

class AutoNodeControllerTest {
    static final class FakeClient implements ExchangeClient {
        volatile boolean available = true, closed;
        int connects, disconnects;
        public CompletableFuture<Response> request(ProxyMessage message, long timeout) {
            if (message.getType() == ProxyMessage.MessageType.CONNECT) connects++;
            if (message.getType() == ProxyMessage.MessageType.DISCONNECT) disconnects++;
            Response reply = Response.ok();
            reply.getAttachments().put("remoteResponse", true);
            return CompletableFuture.completedFuture(reply);
        }
        public void send(ProxyMessage message) { }
        public void close() { closed = true; available = false; }
        public boolean isAvailable() { return available && !closed; }
        public Client getClient() { return null; }
    }

    static ProxyConfig config(String mode, String selected) {
        ProxyConfig config = new ProxyConfig();
        config.setConnectionsPerNode(1);
        config.getSystemProxy().setEnabled(false);
        for (String id : Arrays.asList("a", "b", "c")) {
            ProxyConfig.RemoteServer node = new ProxyConfig.RemoteServer();
            node.setId(id); node.setHost("localhost"); node.setPort(9090);
            node.setTransport("http3"); node.setCipher("none");
            config.getRemoteServers().add(node);
        }
        ProxyConfig.NodeSelection selection = new ProxyConfig.NodeSelection();
        selection.setMode(mode); selection.setSelectedNodeId(selected); selection.setPreferredNodeId("a");
        config.setNodeSelection(selection);
        return config;
    }

    static void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(5);
        assertTrue(condition.getAsBoolean(), "Controller condition not reached");
    }

    @Test void preconnectsAllButOnlyCurrentReceivesBusinessAndStandbysSurviveFailover() throws Exception {
        Map<String, FakeClient> clients = new ConcurrentHashMap<>();
        AtomicLong clock = new AtomicLong(100000);
        try (Http3NodeController controller = new Http3NodeController(config("auto", "a"), node -> {
            FakeClient client = new FakeClient(); clients.put(node.getId(), client); return client;
        }, clock::get)) {
            await(() -> clients.size() == 3 && controller.getClusterInvoker().getAvailableInvokers().size() == 3);
            await(() -> "a".equals(controller.getCurrentNodeId()));
            Invocation connect = new Invocation("target", 443, null, ProxyMessage.MessageType.CONNECT);
            connect.setAttachment("streamId", 123L);
            assertTrue(controller.getClusterInvoker().invoke(connect).join().isSuccess());
            assertEquals(1, clients.get("a").connects);
            assertEquals(0, clients.get("b").connects);
            clients.get("a").available = false;
            controller.tickForTest();
            clock.addAndGet(4999); controller.tickForTest();
            assertEquals("a", controller.getCurrentNodeId());
            clock.incrementAndGet(); controller.tickForTest();
            assertNotEquals("a", controller.getCurrentNodeId());
            assertTrue(clients.get("a").closed);
            assertFalse(clients.get("b").closed);
            assertFalse(clients.get("c").closed);
            assertEquals(0, controller.getActiveStreamCount());
        }
        assertTrue(clients.values().stream().allMatch(c -> c.closed));
    }

    @Test void autoToManualStopsOldNodeAndKeepsOnlyChosenMember() throws Exception {
        Map<String, FakeClient> clients = new ConcurrentHashMap<>();
        try (Http3NodeController controller = new Http3NodeController(config("auto", "a"), node -> {
            FakeClient client = new FakeClient(); clients.put(node.getId(), client); return client;
        }, () -> 100000)) {
            await(() -> controller.getClusterInvoker().getAvailableInvokers().size() == 3);
            FakeClient oldA = clients.get("a"), oldB = clients.get("b"), oldC = clients.get("c");
            controller.apply(config("manual", "b"));
            await(() -> controller.getClusterInvoker().isAvailable());
            assertEquals("manual", controller.getMode());
            assertEquals("b", controller.getCurrentNodeId());
            assertEquals(1, controller.getClusterInvoker().getAvailableInvokers().size());
            assertTrue(oldA.closed);
            assertTrue(oldC.closed);
            // Both reusing and reconnecting the target are valid; a closed target must be replaced.
            if (oldB.closed) assertNotSame(oldB, clients.get("b"));
            controller.tickForTest();
            assertEquals("b", controller.getCurrentNodeId());
        }
    }

    @Test void manualToAutoClosesOldClientAndInstallsPreferredBeforeBusinessConnect() throws Exception {
        Map<String, FakeClient> clients = new ConcurrentHashMap<>();
        try (Http3NodeController controller = new Http3NodeController(config("manual", "b"), node -> {
            FakeClient client = new FakeClient(); clients.put(node.getId(), client); return client;
        }, () -> 100000)) {
            FakeClient oldB = clients.get("b");
            Invocation connect = new Invocation("target", 443, null, ProxyMessage.MessageType.CONNECT);
            connect.setAttachment("streamId", 300L);
            assertTrue(controller.getClusterInvoker().invoke(connect).join().isSuccess());
            controller.apply(config("auto", "b"));
            await(() -> controller.getClusterInvoker().getAvailableInvokers().size() == 3
                    && "a".equals(controller.getCurrentNodeId()));
            assertTrue(oldB.closed);
            assertNotSame(oldB, clients.get("b"));
            assertEquals(0, oldB.disconnects, "Switch closes the client without per-stream retirement requests");
            assertEquals(0, controller.getActiveStreamCount());
            connect.setAttachment("streamId", 301L);
            assertTrue(controller.getClusterInvoker().invoke(connect).join().isSuccess());
            assertEquals(1, clients.get("a").connects);
            assertEquals(0, clients.get("b").connects);
            assertEquals(0, clients.get("c").connects);
        }
    }

    @Test void pendingPreferredInitializationDoesNotLetConnectChooseStandby() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        Map<String, FakeClient> clients = new ConcurrentHashMap<>();
        try (Http3NodeController controller = new Http3NodeController(config("auto", "a"), node -> {
            if ("a".equals(node.getId())) {
                try {
                    if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Test release timed out");
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(error);
                }
            }
            FakeClient client = new FakeClient(); clients.put(node.getId(), client); return client;
        }, () -> 100000)) {
            await(() -> controller.getClusterInvoker().getAvailableInvokers().size() == 2);
            assertFalse(controller.getClusterInvoker().isAvailable());
            Invocation connect = new Invocation("target", 443, null, ProxyMessage.MessageType.CONNECT);
            connect.setAttachment("streamId", 302L);
            assertFalse(controller.getClusterInvoker().invoke(connect).join().isSuccess());
            assertEquals(0, clients.get("b").connects);
            assertEquals(0, clients.get("c").connects);
            assertEquals(0, controller.getActiveStreamCount());
            release.countDown();
            await(() -> "a".equals(controller.getCurrentNodeId()));
            assertTrue(controller.getClusterInvoker().invoke(connect).join().isSuccess());
            assertEquals(1, clients.get("a").connects);
        } finally { release.countDown(); }
    }

    @Test void streamStatisticsUseBrowserRegistryInsteadOfPendingInvocationCount() throws Exception {
        com.proxy.local.handler.StreamChannelRegistry registry = com.proxy.local.handler.StreamChannelRegistry.getInstance();
        io.netty.channel.embedded.EmbeddedChannel browser = new io.netty.channel.embedded.EmbeddedChannel(
                new io.netty.channel.ChannelInboundHandlerAdapter());
        long id = registry.nextStreamId();
        try (Http3NodeController controller = new Http3NodeController(config("manual", "a"),
                node -> new FakeClient(), () -> 100000)) {
            assertEquals(0, registry.size());
            registry.register(id, browser.pipeline().firstContext());
            assertEquals(1, controller.getActiveStreamCount());
            assertEquals(0, controller.getClusterInvoker().getActiveCount());
            browser.close();
            browser.runPendingTasks();
            assertEquals(0, controller.getActiveStreamCount());
        } finally {
            registry.unregister(id);
            browser.finishAndReleaseAll();
        }
    }

    @Test void reenteringAutoResetsSelectionEpochAndWaitsForPreferred() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicBoolean delayPreferred = new java.util.concurrent.atomic.AtomicBoolean();
        try (Http3NodeController controller = new Http3NodeController(config("auto", "a"), node -> {
            if (delayPreferred.get() && "a".equals(node.getId())) {
                try {
                    if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Test release timed out");
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(error);
                }
            }
            return new FakeClient();
        }, () -> 100000)) {
            await(() -> "a".equals(controller.getCurrentNodeId()));
            controller.apply(config("manual", "b"));
            await(() -> controller.getClusterInvoker().isAvailable());
            delayPreferred.set(true);
            controller.apply(config("auto", "b"));
            await(() -> controller.getClusterInvoker().getAvailableInvokers().size() == 2);
            assertEquals("", controller.getCurrentNodeId());
            assertFalse(controller.getClusterInvoker().isAvailable());
            release.countDown();
            // The clock has not advanced: a previous epoch's selection throttle must not block this one.
            await(() -> "a".equals(controller.getCurrentNodeId()));
            assertTrue(controller.getClusterInvoker().isAvailable());
        } finally { release.countDown(); }
    }

    @Test void autoToSameManualNodeStillClosesAndRecreatesClient() throws Exception {
        List<FakeClient> clients = new CopyOnWriteArrayList<>();
        try (Http3NodeController controller = new Http3NodeController(config("auto", "a"), node -> {
            FakeClient client = new FakeClient(); clients.add(client); return client;
        }, () -> 100000)) {
            await(() -> controller.getClusterInvoker().getAvailableInvokers().size() == 3);
            List<FakeClient> previous = new ArrayList<>(clients);
            controller.apply(config("manual", "a"));
            await(() -> controller.getClusterInvoker().isAvailable());
            assertTrue(previous.stream().allMatch(c -> c.closed));
            assertEquals(1, controller.getClusterInvoker().getAvailableInvokers().size());
        }
    }

    @Test void autoNodeEditsRequireRestartAndDoNotRebuildMembers() throws Exception {
        try (Http3NodeController controller = new Http3NodeController(config("auto", "a"), node -> new FakeClient(), () -> 100000)) {
            await(() -> controller.getClusterInvoker().getAvailableInvokers().size() == 3);
            ProxyConfig changed = config("auto", "a");
            changed.getRemoteServers().remove(2);
            assertThrows(IllegalArgumentException.class, () -> controller.apply(changed));
            assertEquals(3, controller.getClusterInvoker().getAvailableInvokers().size());
        }
    }

    @Test void initialPreferredFailureAllowsHealthyBackupWithoutWaitingForAll() throws Exception {
        try (Http3NodeController controller = new Http3NodeController(config("auto", "a"), node -> {
            if ("a".equals(node.getId())) throw new IllegalStateException("unreachable");
            return new FakeClient();
        }, () -> 100000)) {
            await(() -> !controller.getCurrentNodeId().isEmpty());
            assertNotEquals("a", controller.getCurrentNodeId());
        }
    }

    @Test void recoveredPreferredReturnsToStandbyWithoutPreemptingHealthyCurrent() throws Exception {
        Map<String, FakeClient> clients = new ConcurrentHashMap<>();
        AtomicLong clock = new AtomicLong(100000);
        try (Http3NodeController controller = new Http3NodeController(config("auto", "a"), node -> {
            FakeClient client = new FakeClient(); clients.put(node.getId(), client); return client;
        }, clock::get)) {
            await(() -> controller.getClusterInvoker().getAvailableInvokers().size() == 3);
            clients.get("a").available = false;
            controller.tickForTest();
            clock.addAndGet(5000); controller.tickForTest();
            String backup = controller.getCurrentNodeId();
            FakeClient oldA = clients.get("a");
            assertNotEquals("a", backup);
            clock.addAndGet(40000); controller.tickForTest();
            await(() -> clients.get("a") != oldA && controller.getClusterInvoker().getAvailableInvokers().size() == 3);
            controller.tickForTest();
            await(() -> clients.get("a").connects == 1);
            controller.tickForTest(); // Drain completed first check before advancing the fake clock.
            clock.addAndGet(5000); controller.tickForTest();
            await(() -> clients.get("a").connects == 2);
            controller.tickForTest();
            assertEquals(backup, controller.getCurrentNodeId());
            assertTrue(controller.getNodeStatesJson().contains("\"nodeId\":\"a\",\"circuit\":\"CLOSED\""));
        }
    }

    @Test void lateAutomaticConnectionCannotRejoinManualCluster() throws Exception {
        CountDownLatch connecting = new CountDownLatch(1), release = new CountDownLatch(1);
        FakeClient late = new FakeClient();
        try (Http3NodeController controller = new Http3NodeController(config("auto", "a"), node -> {
            if ("a".equals(node.getId())) {
                connecting.countDown();
                try { release.await(5, TimeUnit.SECONDS); }
                catch (InterruptedException error) { Thread.currentThread().interrupt(); }
                return late;
            }
            return new FakeClient();
        }, () -> 100000)) {
            assertTrue(connecting.await(5, TimeUnit.SECONDS));
            controller.apply(config("manual", "b"));
            release.countDown();
            await(() -> late.closed);
            await(() -> controller.getClusterInvoker().isAvailable());
            assertEquals("manual", controller.getMode());
            assertEquals("b", controller.getCurrentNodeId());
            assertEquals(1, controller.getClusterInvoker().getAvailableInvokers().size());
        } finally { release.countDown(); }
    }
}
