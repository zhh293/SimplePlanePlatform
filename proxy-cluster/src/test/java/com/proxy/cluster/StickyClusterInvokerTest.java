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
        CompletableFuture<Response> connect = CompletableFuture.completedFuture(Response.ok());
        CompletableFuture<Response> disconnect = CompletableFuture.completedFuture(Response.ok());
        public CompletableFuture<Response> invoke(Invocation invocation) {
            calls++;
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
        assertTrue(cluster.invoke(message(1, MessageType.CONNECT)).join().isSuccess());
        cluster.setInvokers(Collections.singletonList(b));
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
