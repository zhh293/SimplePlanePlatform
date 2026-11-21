package com.proxy.cluster;

import com.proxy.cluster.filter.RouterFilter;
import com.proxy.common.filter.Invocation;
import com.proxy.common.filter.Invoker;
import com.proxy.common.filter.Response;
import com.proxy.common.model.ProxyMessage;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ExplicitUdpRouteTest {
    @Test
    void ingressProxyDecisionSurvivesLegacyPrivateHostRuleForEveryUdpMessage() {
        RouterFilter filter = new RouterFilter();
        AtomicInteger calls = new AtomicInteger();
        Invoker target = call -> {
            calls.incrementAndGet();
            return CompletableFuture.completedFuture(Response.ok(new byte[]{1}));
        };
        for (ProxyMessage.MessageType type : new ProxyMessage.MessageType[]{
                ProxyMessage.MessageType.UDP_CONNECT,
                ProxyMessage.MessageType.DATA,
                ProxyMessage.MessageType.DISCONNECT}) {
            Invocation call = new Invocation("127.0.0.1", 39992, new byte[0], type);
            call.setAttachment("route", "proxy");
            assertArrayEquals(new byte[]{1}, filter.invoke(target, call).join().getData());
        }
        assertEquals(3, calls.get());
        Invocation legacy = new Invocation("127.0.0.1", 80, null, ProxyMessage.MessageType.CONNECT);
        assertEquals("direct", filter.invoke(target, legacy).join().getAttachments().get("route"));
        assertEquals(3, calls.get());
    }
}
