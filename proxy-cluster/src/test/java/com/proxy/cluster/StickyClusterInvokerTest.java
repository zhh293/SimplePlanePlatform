package com.proxy.cluster;

import com.proxy.common.filter.Invocation;
import com.proxy.common.filter.Invoker;
import com.proxy.common.filter.Response;
import com.proxy.common.model.ProxyMessage.MessageType;
import org.junit.jupiter.api.Test;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class StickyClusterInvokerTest {
    private static final class Connection implements Invoker {
        int calls, activeCalls;
        boolean available = true;
        RuntimeException failure;
        Invocation last;
        CompletableFuture<Response> result = CompletableFuture.completedFuture(Response.ok());
        public CompletableFuture<Response> invoke(Invocation invocation) {
            calls++;
            last = invocation;
            if (failure != null) throw failure;
            return result;
        }
        public boolean isAvailable() { return available; }
        public int getActiveCount() { return activeCalls; }
    }

    private static Invocation message(long id, MessageType type) {
        Invocation invocation = new Invocation("target", 443, null, type);
        invocation.setAttachment("streamId", id);
        return invocation;
    }

    private static StickyClusterInvoker cluster(Connection current, Invoker... members) {
        StickyClusterInvoker cluster = new StickyClusterInvoker();
        cluster.setInvokers(Arrays.asList(members));
        cluster.setCurrentInvoker(current);
        return cluster;
    }

    @Test void allBusinessEventsRequireAnInstalledCurrent() {
        Connection a = new Connection(), b = new Connection();
        StickyClusterInvoker cluster = cluster(null, a, b);
        cluster.setLoadBalance((members, invocation, excluded) -> {
            throw new AssertionError("Business dispatch must not select a node");
        });
        assertFalse(cluster.isAvailable());
        for (MessageType type : Arrays.asList(MessageType.CONNECT, MessageType.DATA, MessageType.DISCONNECT)) {
            assertFalse(cluster.invoke(message(1, type)).join().isSuccess());
        }
        assertEquals(0, a.calls);
        assertEquals(0, b.calls);
    }

    @Test void allEventsArePassedUnchangedToCurrentWithoutClusterStreamState() {
        Connection current = new Connection();
        StickyClusterInvoker cluster = cluster(current, current);
        // Validation of opened streams belongs to the client, not this forwarding cluster.
        for (MessageType type : Arrays.asList(MessageType.DATA, MessageType.CONNECT, MessageType.DISCONNECT)) {
            Invocation invocation = message(2, type);
            assertSame(current.result, cluster.invoke(invocation));
            assertSame(invocation, current.last);
        }
        assertEquals(3, current.calls);
    }

    @Test void currentReplacementDoesNotRetainOldInvokerOrEmitDisconnects() {
        Connection a = new Connection(), b = new Connection();
        StickyClusterInvoker cluster = cluster(a, a, b);
        cluster.invoke(message(3, MessageType.CONNECT)).join();
        cluster.setCurrentInvoker(null);
        assertFalse(cluster.invoke(message(3, MessageType.DATA)).join().isSuccess());
        cluster.setCurrentInvoker(b);
        // B's real transport rejects A's old stream ID; the cluster retains no ownership table.
        cluster.invoke(message(3, MessageType.DATA)).join();
        cluster.invoke(message(3, MessageType.DISCONNECT)).join();
        assertEquals(1, a.calls, "No synthetic per-stream retirement");
        assertEquals(2, b.calls);
    }

    @Test void businessDispatchDoesNotInspectStandbys() {
        Connection current = new Connection();
        Invoker standby = new Invoker() {
            public CompletableFuture<Response> invoke(Invocation invocation) { throw new AssertionError(); }
            public boolean isAvailable() { throw new AssertionError("Do not scan standby health"); }
        };
        StickyClusterInvoker cluster = cluster(current, current, standby);
        assertTrue(cluster.isAvailable());
        for (MessageType type : Arrays.asList(MessageType.CONNECT, MessageType.DATA, MessageType.DISCONNECT)) {
            assertTrue(cluster.invoke(message(4, type)).join().isSuccess());
        }
        assertEquals(3, current.calls);
    }

    @Test void removedAndUnavailableCurrentNeverFallBackToHealthyMember() {
        Connection a = new Connection(), b = new Connection();
        StickyClusterInvoker cluster = cluster(a, a, b);
        a.available = false;
        for (MessageType type : Arrays.asList(MessageType.CONNECT, MessageType.DATA, MessageType.DISCONNECT)) {
            assertFalse(cluster.invoke(message(5, type)).join().isSuccess());
        }
        assertFalse(cluster.isAvailable());
        a.available = true;
        cluster.removeInvoker(a);
        assertFalse(cluster.isAvailable());
        assertFalse(cluster.invoke(message(5, MessageType.CONNECT)).join().isSuccess());
        assertEquals(0, a.calls);
        assertEquals(0, b.calls);
        assertThrows(IllegalArgumentException.class, () -> cluster.setCurrentInvoker(a));
    }

    @Test void explicitSelectionRetainsHealthFilteringAndLoadBalance() {
        Connection a = new Connection(), b = new Connection(), c = new Connection();
        StickyClusterInvoker cluster = cluster(null, a, b, c);
        a.available = false;
        AtomicInteger selections = new AtomicInteger();
        cluster.setLoadBalance((members, invocation, excluded) -> {
            assertEquals(Arrays.asList(b, c), members);
            assertNull(invocation);
            selections.incrementAndGet();
            return c;
        });
        assertSame(c, cluster.selectCurrent(Arrays.asList(a, b, c), a));
        assertTrue(cluster.invoke(message(6, MessageType.CONNECT)).join().isSuccess());
        assertEquals(1, selections.get());
        assertSame(b, cluster.selectCurrent(Arrays.asList(a, b, c), b));
        assertEquals(1, selections.get());
        assertNull(cluster.selectCurrent(Collections.emptyList(), null));
        assertFalse(cluster.isAvailable());
    }

    @Test void invalidIdsAndMessageTypesAreRejectedBeforeDispatch() {
        Connection current = new Connection();
        StickyClusterInvoker cluster = cluster(current, current);
        Invocation invocation = new Invocation("target", 443, null, MessageType.CONNECT);
        assertFalse(cluster.invoke(invocation).join().isSuccess());
        invocation.setAttachment("streamId", "invalid");
        assertFalse(cluster.invoke(invocation).join().isSuccess());
        invocation.setAttachment("streamId", 0L);
        assertFalse(cluster.invoke(invocation).join().isSuccess());
        invocation.setAttachment("streamId", 7L);
        invocation.setType(null);
        assertFalse(cluster.invoke(invocation).join().isSuccess());
        assertEquals(0, current.calls);
    }

    @Test void synchronousAndAsynchronousFailuresAreReturnedWithoutRetry() {
        Connection current = new Connection(), standby = new Connection();
        StickyClusterInvoker cluster = cluster(current, current, standby);
        current.failure = new IllegalStateException("client closed");
        assertThrows(CompletionException.class, () -> cluster.invoke(message(8, MessageType.CONNECT)).join());
        current.failure = null;
        current.result = new CompletableFuture<>();
        CompletableFuture<Response> result = cluster.invoke(message(8, MessageType.CONNECT));
        current.result.completeExceptionally(new IllegalStateException("transport failed"));
        assertThrows(CompletionException.class, result::join);
        current.result = CompletableFuture.completedFuture(Response.error("target refused"));
        assertFalse(cluster.invoke(message(9, MessageType.CONNECT)).join().isSuccess());
        assertEquals(0, standby.calls);
    }

    @Test void unfinishedConnectRemainsOwnedByItsClientAcrossReplacement() {
        Connection a = new Connection(), b = new Connection();
        StickyClusterInvoker cluster = cluster(a, a, b);
        a.result = new CompletableFuture<>();
        CompletableFuture<Response> result = cluster.invoke(message(10, MessageType.CONNECT));
        cluster.setCurrentInvoker(null);
        // Real ExchangeClient.close completes its own pending CONNECT exceptionally.
        a.result.completeExceptionally(new java.nio.channels.ClosedChannelException());
        cluster.setCurrentInvoker(b);
        assertThrows(CompletionException.class, result::join);
        assertTrue(cluster.invoke(message(11, MessageType.CONNECT)).join().isSuccess());
        assertEquals(1, a.calls);
        assertEquals(1, b.calls);
    }

    @Test void currentCannotBeRetiredBeforeDispatchRegistersItsPendingRequest() throws Exception {
        StickyClusterInvoker cluster = new StickyClusterInvoker();
        java.util.concurrent.CountDownLatch entered = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch switching = new java.util.concurrent.CountDownLatch(1);
        CompletableFuture<Response> pending = new CompletableFuture<>();
        java.util.concurrent.atomic.AtomicReference<CompletableFuture<Response>> registered = new java.util.concurrent.atomic.AtomicReference<>();
        Invoker old = invocation -> {
            entered.countDown();
            try { assertTrue(release.await(5, java.util.concurrent.TimeUnit.SECONDS)); }
            catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IllegalStateException(error); }
            registered.set(pending);
            return pending;
        };
        cluster.setInvokers(Collections.singletonList(old));
        cluster.setCurrentInvoker(old);
        java.util.concurrent.ExecutorService threads = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            java.util.concurrent.Future<CompletableFuture<Response>> dispatch = threads.submit(
                    () -> cluster.invoke(message(12, MessageType.CONNECT)));
            assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS));
            java.util.concurrent.Future<?> retirement = threads.submit(() -> {
                switching.countDown();
                cluster.setCurrentInvoker(null);
                assertSame(pending, registered.get(), "Client registration must precede retirement");
                pending.completeExceptionally(new java.nio.channels.ClosedChannelException());
            });
            assertTrue(switching.await(5, java.util.concurrent.TimeUnit.SECONDS));
            assertThrows(java.util.concurrent.TimeoutException.class,
                    () -> retirement.get(100, java.util.concurrent.TimeUnit.MILLISECONDS));
            release.countDown();
            CompletableFuture<Response> result = dispatch.get(5, java.util.concurrent.TimeUnit.SECONDS);
            retirement.get(5, java.util.concurrent.TimeUnit.SECONDS);
            assertThrows(CompletionException.class, result::join);
            assertFalse(pending.complete(Response.ok()), "Late response cannot revive the cancelled client request");
            assertNull(cluster.getCurrentInvoker());
        } finally {
            release.countDown();
            threads.shutdownNow();
            assertTrue(threads.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS));
        }
    }

    @Test void activeInvocationCountDelegatesToCurrentRatherThanCountingStreams() {
        Connection a = new Connection(), b = new Connection();
        a.activeCalls = 2;
        b.activeCalls = 5;
        StickyClusterInvoker cluster = cluster(a, a, b);
        assertEquals(2, cluster.getActiveCount());
        cluster.setCurrentInvoker(b);
        assertEquals(5, cluster.getActiveCount());
        cluster.setCurrentInvoker(null);
        assertEquals(0, cluster.getActiveCount());
    }
}
