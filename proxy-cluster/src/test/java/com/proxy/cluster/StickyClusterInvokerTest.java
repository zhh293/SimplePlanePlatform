package com.proxy.cluster;

import com.proxy.common.filter.Invocation;
import com.proxy.common.filter.Filter;
import com.proxy.common.filter.FilterChainBuilder;
import com.proxy.common.filter.Invoker;
import com.proxy.common.filter.Response;
import com.proxy.common.model.ProxyMessage.MessageType;
import com.proxy.common.spi.ExtensionLoader;
import org.junit.jupiter.api.Test;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.CompletableFuture;
import static org.junit.jupiter.api.Assertions.*;

class StickyClusterInvokerTest {
    @Test void explicitControllerSelectionStillUsesLoadBalanceButBusinessConnectDoesNot() {
        StickyClusterInvoker cluster = new StickyClusterInvoker();
        Connection a = new Connection(), b = new Connection();
        cluster.setInvokers(Arrays.asList(a, b));
        java.util.concurrent.atomic.AtomicInteger selections = new java.util.concurrent.atomic.AtomicInteger();
        cluster.setLoadBalance((members, invocation, excluded) -> {
            assertNull(invocation, "Selection belongs to the controller, not a business request");
            selections.incrementAndGet();
            return b;
        });
        assertSame(b, cluster.selectCurrent(Arrays.asList(a, b), null));
        assertSame(b, cluster.getCurrentInvoker());
        assertTrue(cluster.invoke(message(1011, MessageType.CONNECT)).join().isSuccess());
        assertEquals(1, selections.get());
        assertEquals(0, a.calls);
        assertEquals(1, b.calls);
        cluster.retireBindings().join();
    }

    @Test void connectRequiresExplicitCurrentEvenWithHealthyMembersAndLoadBalance() {
        StickyClusterInvoker cluster = new StickyClusterInvoker();
        Connection a = new Connection(), b = new Connection();
        cluster.setInvokers(Arrays.asList(a, b));
        cluster.setLoadBalance((members, invocation, excluded) -> {
            fail("Business CONNECT must not select a node");
            return a;
        });
        assertFalse(cluster.isAvailable());
        assertFalse(cluster.invoke(message(1001, MessageType.CONNECT)).join().isSuccess());
        assertEquals(0, a.calls);
        assertEquals(0, b.calls);
        assertEquals(0, cluster.getBindingCount());
        cluster.setCurrentInvoker(a);
        assertTrue(cluster.isAvailable());
        assertTrue(cluster.invoke(message(1002, MessageType.CONNECT)).join().isSuccess());
        cluster.setCurrentInvoker(null);
        assertFalse(cluster.invoke(message(1003, MessageType.CONNECT)).join().isSuccess());
        // Pausing new CONNECT does not discard an existing stream's ownership.
        assertTrue(cluster.invoke(message(1002, MessageType.DATA)).join().isSuccess());
        assertTrue(cluster.invoke(message(1002, MessageType.DISCONNECT)).join().isSuccess());
        assertEquals(3, a.calls);
        assertEquals(0, b.calls);
        assertEquals(0, cluster.getBindingCount());
    }

    @Test void businessConnectDoesNotInspectStandbyAvailability() {
        StickyClusterInvoker cluster = new StickyClusterInvoker();
        Connection current = new Connection();
        Invoker standby = new Invoker() {
            public CompletableFuture<Response> invoke(Invocation invocation) {
                throw new AssertionError("Standby must not receive business traffic");
            }
            public boolean isAvailable() {
                throw new AssertionError("Business CONNECT must not scan standbys");
            }
            public int getActiveCount() { return 0; }
        };
        cluster.setInvokers(Arrays.asList(current, standby));
        cluster.setCurrentInvoker(current);
        assertTrue(cluster.isAvailable());
        assertTrue(cluster.invoke(message(1004, MessageType.CONNECT)).join().isSuccess());
        assertEquals(1, current.calls);
        cluster.retireBindings().join();
    }

    @Test void removedOrUnavailableCurrentRejectsConnectWithoutBindingOrFallback() {
        StickyClusterInvoker cluster = new StickyClusterInvoker();
        Connection a = new Connection(), b = new Connection();
        cluster.setInvokers(Arrays.asList(a, b));
        cluster.setCurrentInvoker(a);
        a.available = false;
        assertFalse(cluster.isAvailable());
        assertFalse(cluster.invoke(message(1005, MessageType.CONNECT)).join().isSuccess());
        a.available = true;
        cluster.removeInvoker(a);
        assertFalse(cluster.isAvailable());
        assertFalse(cluster.invoke(message(1006, MessageType.CONNECT)).join().isSuccess());
        assertEquals(0, a.calls);
        assertEquals(0, b.calls);
        assertEquals(0, cluster.getBindingCount());
        cluster.setCurrentInvoker(b);
        assertTrue(cluster.invoke(message(1007, MessageType.CONNECT)).join().isSuccess());
        cluster.retireBindings().join();
    }

    @Test void invalidStreamIdsAreRejectedBeforeCallingCurrent() {
        StickyClusterInvoker cluster = new StickyClusterInvoker();
        Connection current = new Connection();
        cluster.setInvokers(Collections.singletonList(current));
        cluster.setCurrentInvoker(current);
        Invocation connect = new Invocation("target", 443, null, MessageType.CONNECT);
        assertFalse(cluster.invoke(connect).join().isSuccess());
        connect.setAttachment("streamId", "invalid");
        assertFalse(cluster.invoke(connect).join().isSuccess());
        connect.setAttachment("streamId", 0L);
        assertFalse(cluster.invoke(connect).join().isSuccess());
        assertEquals(0, current.calls);
        assertEquals(0, cluster.getBindingCount());
    }

    @Test void synchronousAndAsynchronousConnectFailuresReleaseBindings() {
        StickyClusterInvoker cluster = new StickyClusterInvoker();
        Connection current = new Connection();
        cluster.setInvokers(Collections.singletonList(current));
        cluster.setCurrentInvoker(current);
        current.failure = new IllegalStateException("transport closed during CONNECT");
        assertThrows(java.util.concurrent.CompletionException.class,
                () -> cluster.invoke(message(1008, MessageType.CONNECT)).join());
        assertEquals(0, cluster.getBindingCount());
        current.failure = null;
        current.connect = new CompletableFuture<>();
        CompletableFuture<Response> pending = cluster.invoke(message(1009, MessageType.CONNECT));
        current.connect.completeExceptionally(new IllegalStateException("connection lost"));
        assertThrows(java.util.concurrent.CompletionException.class, pending::join);
        assertEquals(0, cluster.getBindingCount());
        current.connect = CompletableFuture.completedFuture(null);
        assertFalse(cluster.invoke(message(1010, MessageType.CONNECT)).join().isSuccess());
        assertEquals(0, cluster.getBindingCount());
    }

    @Test void fixedCurrentDoesNotRotateStreamsAndPausePreservesStandbys() {
        StickyClusterInvoker cluster = new StickyClusterInvoker();
        Connection a = new Connection(), b = new Connection();
        cluster.setInvokers(Arrays.asList(a, b));
        assertSame(a, cluster.selectCurrent(Arrays.asList(a, b), a));
        cluster.invoke(message(101, MessageType.CONNECT)).join();
        cluster.invoke(message(102, MessageType.CONNECT)).join();
        assertEquals(2, a.calls);
        assertEquals(0, b.calls);
        cluster.setCurrentInvoker(null);
        assertFalse(cluster.isAvailable());
        assertFalse(cluster.invoke(message(103, MessageType.CONNECT)).join().isSuccess());
        assertEquals(2, cluster.getAvailableInvokers().size());
        cluster.retireBindings().join();
        assertEquals(0, cluster.getBindingCount());
        assertEquals(0, b.calls);
        cluster.selectCurrent(Collections.singletonList(b), null);
        assertTrue(cluster.invoke(message(104, MessageType.CONNECT)).join().isSuccess());
        assertEquals(1, b.calls);
    }

    @Test void selectionExcludesUnhealthyCandidatesAndDoesNotFallBackPerStream() {
        StickyClusterInvoker cluster = new StickyClusterInvoker();
        Connection a = new Connection(), b = new Connection(), c = new Connection();
        cluster.setInvokers(Arrays.asList(a, b, c));
        a.available = false;
        assertSame(b, cluster.selectCurrent(Arrays.asList(a, b), a));
        b.available = false;
        assertFalse(cluster.isAvailable());
        assertFalse(cluster.invoke(message(105, MessageType.CONNECT)).join().isSuccess());
        assertEquals(0, c.calls);
        assertNull(cluster.selectCurrent(Collections.emptyList(), null));
        assertThrows(IllegalArgumentException.class, () -> cluster.setCurrentInvoker(new Connection()));
    }

    private static final class Connection implements Invoker {
        int calls;
        boolean available = true;
        RuntimeException failure;
        CompletableFuture<Response> connect = CompletableFuture.completedFuture(Response.ok());
        CompletableFuture<Response> disconnect = CompletableFuture.completedFuture(Response.ok());
        public CompletableFuture<Response> invoke(Invocation invocation) {
            calls++;
            if (failure != null) throw failure;
            return invocation.getType() == MessageType.CONNECT ? connect
                    : invocation.getType() == MessageType.DISCONNECT ? disconnect
                    : CompletableFuture.completedFuture(Response.ok());
        }
        public boolean isAvailable() { return available; }
        public int getActiveCount() { return 0; }
    }

    private static Invocation message(long id, MessageType type) {
        Invocation invocation = new Invocation("target", 443, null, type);
        invocation.setAttachment("streamId", id);
        return invocation;
    }

    @Test void dataAndDisconnectRetainOriginalConnectionAfterSelectionChanges() {
        StickyClusterInvoker cluster = new StickyClusterInvoker();
        Connection a = new Connection(), b = new Connection();
        cluster.setInvokers(Arrays.asList(a, b));
        cluster.setCurrentInvoker(a);
        assertTrue(cluster.invoke(message(1, MessageType.CONNECT)).join().isSuccess());
        cluster.setInvokers(Collections.singletonList(b));
        cluster.setCurrentInvoker(b);
        cluster.invoke(message(1, MessageType.DATA)).join();
        cluster.invoke(message(1, MessageType.DISCONNECT)).join();
        assertEquals(3, a.calls);
        assertEquals(0, b.calls);
        assertEquals(0, cluster.getBindingCount());
    }

    @Test void unavailableBindingNeverSendsDataToAnotherConnection() {
        StickyClusterInvoker cluster = new StickyClusterInvoker();
        Connection a = new Connection(), b = new Connection();
        cluster.setInvokers(Arrays.asList(a, b));
        cluster.setCurrentInvoker(a);
        cluster.invoke(message(1, MessageType.CONNECT)).join();
        a.available = false;
        cluster.invoke(message(1, MessageType.DATA)).join();
        assertEquals(2, a.calls);
        assertEquals(0, b.calls);
        assertFalse(cluster.invoke(message(2, MessageType.DATA)).join().isSuccess());
    }

    @Test void cancelledConnectCannotBeActivatedByLateSuccess() {
        StickyClusterInvoker cluster = new StickyClusterInvoker();
        Connection a = new Connection();
        a.connect = new CompletableFuture<>();
        cluster.setInvokers(Collections.singletonList(a));
        cluster.setCurrentInvoker(a);
        CompletableFuture<Response> connecting = cluster.invoke(message(1, MessageType.CONNECT));
        assertFalse(cluster.invoke(message(1, MessageType.DATA)).join().isSuccess());
        cluster.unbind(1);
        a.connect.complete(Response.ok());
        assertFalse(connecting.join().isSuccess());
        assertEquals(0, cluster.getBindingCount());
    }

    @Test void failedAndDuplicateConnectDoNotLeakBindings() {
        StickyClusterInvoker cluster = new StickyClusterInvoker();
        Connection a = new Connection();
        a.connect = new CompletableFuture<>();
        cluster.setInvokers(Collections.singletonList(a));
        cluster.setCurrentInvoker(a);
        CompletableFuture<Response> connecting = cluster.invoke(message(1, MessageType.CONNECT));
        assertFalse(cluster.invoke(message(1, MessageType.CONNECT)).join().isSuccess());
        a.connect.complete(Response.error("Target refused"));
        assertFalse(connecting.join().isSuccess());
        assertEquals(0, cluster.getBindingCount());
        assertEquals(1, a.calls);
    }

    @Test void retiringBindingsCleansThroughOldConnectionAndInvalidatesLateConnect() {
        StickyClusterInvoker cluster = new StickyClusterInvoker();
        Connection a = new Connection(), b = new Connection();
        a.connect = new CompletableFuture<>();
        cluster.setInvokers(Collections.singletonList(a));
        cluster.setCurrentInvoker(a);
        CompletableFuture<Response> connecting = cluster.invoke(message(1, MessageType.CONNECT));
        cluster.setInvokers(Collections.singletonList(b));
        cluster.retireBindings();
        assertEquals(2, a.calls);
        assertEquals(0, b.calls);
        assertEquals(0, cluster.getBindingCount());
        a.connect.complete(Response.ok());
        assertFalse(connecting.join().isSuccess());
        cluster.retireBindings();
        assertEquals(2, a.calls);
    }

    @Test void retirementTraversesClientFiltersAndWaitsForDisconnectAcknowledgement() {
        StickyClusterInvoker cluster = new StickyClusterInvoker();
        Connection connection = new Connection();
        connection.disconnect = new CompletableFuture<>();
        FilterChainBuilder builder = ExtensionLoader.getLoader(FilterChainBuilder.class).getDefaultExtension();
        cluster.setInvokers(Collections.singletonList(builder.build(connection,
                ExtensionLoader.getLoader(Filter.class).getActivateExtensions("client"))));
        cluster.setCurrentInvoker(cluster.getAvailableInvokers().get(0));
        cluster.invoke(message(1, MessageType.CONNECT)).join();
        CompletableFuture<Void> retiring = cluster.retireBindings();
        assertEquals(2, connection.calls, "DISCONNECT must pass the real client filters");
        assertEquals(0, cluster.getBindingCount());
        assertFalse(retiring.isDone());
        connection.disconnect.complete(Response.ok());
        assertTrue(retiring.isDone());
        retiring.join();
    }
}
