package com.proxy.local.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.yaml.snakeyaml.Yaml;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.proxy.local.config.AutoNodeControllerTest.*;

class NetworkRecoveryTest {
    @TempDir Path directory;

    @Test void manualResetClosesOldConnectionAndRetainsSelection() throws Exception {
        List<FakeClient> clients = new CopyOnWriteArrayList<>();
        try (Http3NodeController controller = new Http3NodeController(config("manual", "b"), node -> {
            assertEquals("b", node.getId());
            FakeClient client = new FakeClient(); clients.add(client); return client;
        }, () -> 100000)) {
            controller.networkChanged();
            await(() -> clients.size() == 2 && "available".equals(controller.getNodeStatus()));
            assertTrue(clients.get(0).closed);
            assertFalse(clients.get(1).closed);
            assertEquals("b", controller.getCurrentNodeId());
        }
        assertTrue(clients.get(1).closed);
    }

    @Test void manualReconnectFailureRetriesWithoutChangingNode() throws Exception {
        AtomicLong clock = new AtomicLong(100000);
        AtomicInteger attempts = new AtomicInteger();
        try (Http3NodeController controller = new Http3NodeController(config("manual", "b"), node -> {
            if (attempts.incrementAndGet() == 2) throw new IllegalStateException("DHCP not ready");
            assertEquals("b", node.getId()); return new FakeClient();
        }, clock::get)) {
            controller.networkChanged();
            await(() -> "unavailable".equals(controller.getNodeStatus()));
            controller.tickForTest(); assertEquals(2, attempts.get());
            clock.addAndGet(360000); controller.tickForTest();
            await(() -> "available".equals(controller.getNodeStatus())
                    && controller.getNodeStatesJson().contains("\"circuit\":\"CLOSED\""));
            assertEquals(3, attempts.get()); assertEquals("b", controller.getCurrentNodeId());
            assertTrue(controller.getNodeStatesJson().contains("\"circuit\":\"CLOSED\""));
        }
    }

    @Test void autoResetDiscardsLateOldConnectionsAndRestoresPreferredNode() throws Exception {
        CountDownLatch releaseOld = new CountDownLatch(1), oldStarted = new CountDownLatch(1);
        AtomicBoolean firstB = new AtomicBoolean(true);
        FakeClient stale = new FakeClient();
        try (Http3NodeController controller = new Http3NodeController(config("auto", "a"), node -> {
            if (node.getId().equals("b") && firstB.getAndSet(false)) {
                oldStarted.countDown();
                try { assertTrue(releaseOld.await(5, TimeUnit.SECONDS)); }
                catch (InterruptedException error) { throw new IllegalStateException(error); }
                return stale;
            }
            return new FakeClient();
        }, () -> 100000)) {
            assertTrue(oldStarted.await(5, TimeUnit.SECONDS));
            await(() -> "a".equals(controller.getCurrentNodeId()));
            controller.networkChanged(); releaseOld.countDown();
            await(() -> stale.closed && "a".equals(controller.getCurrentNodeId())
                    && controller.getClusterInvoker().getAvailableInvokers().size() == 3);
        } finally { releaseOld.countDown(); }
    }

    @Test void generationFileResetsOnceAndSurvivesAtomicReplacement() throws Exception {
        ProxyConfig config = config("manual", "a");
        Path file = directory.resolve("proxy-java.yml");
        Files.write(file, new Yaml().dumpAsMap(config).replaceAll("!!com\\.proxy\\.[\\w.$]+", "").getBytes(StandardCharsets.UTF_8));
        AtomicInteger attempts = new AtomicInteger();
        try (Http3NodeController controller = new Http3NodeController(config, node -> {
            attempts.incrementAndGet(); return new FakeClient();
        }, () -> 100000);
             NodeConfigWatcher watcher = new NodeConfigWatcher(file, controller, config)) {
            Path reset = directory.resolve("proxy-java.yml.network-reset");
            Files.write(reset, "generation-1".getBytes(StandardCharsets.UTF_8));
            await(() -> attempts.get() == 2 && "available".equals(controller.getNodeStatus()));
            Thread.sleep(1100); assertEquals(2, attempts.get());
            Path next = directory.resolve("next");
            Files.write(next, "generation-2".getBytes(StandardCharsets.UTF_8));
            Files.move(next, reset, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            await(() -> attempts.get() == 3);
            assertEquals("a", controller.getCurrentNodeId());
        }
    }
}
