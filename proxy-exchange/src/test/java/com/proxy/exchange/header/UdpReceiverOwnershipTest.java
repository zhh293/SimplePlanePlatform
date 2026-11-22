package com.proxy.exchange.header;

import com.proxy.common.exchange.DatagramReceiver;
import com.proxy.common.filter.Response;
import com.proxy.common.model.ProxyMessage;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class UdpReceiverOwnershipTest {
    static final class Receiver implements DatagramReceiver {
        final AtomicInteger packets=new AtomicInteger(), closed=new AtomicInteger();
        @Override public void onDatagram(byte[] bytes) {assertNotNull(bytes);packets.incrementAndGet();}
        @Override public void onClosed(Throwable cause) {closed.incrementAndGet();}
    }
    static void own(ExchangeHandler handler,long streamId) {
        long id=RequestIdGenerator.next(); DefaultFuture pending=DefaultFuture.newFuture(id,10000);
        handler.trackRequest(id,streamId,pending,true); DefaultFuture.received(id,Response.ok());
    }
    @Test void emptyPushRequiresOwnershipAndDisconnectOnlyClosesItsClient() {
        ExchangeHandler a=new ExchangeHandler(), b=new ExchangeHandler();
        ConcurrentHashMap<Long,DatagramReceiver> registry=new ConcurrentHashMap<>();
        Receiver ra=new Receiver(),rb=new Receiver();registry.put(1L,ra);registry.put(2L,rb);
        a.setDatagramRegistry(registry);b.setDatagramRegistry(registry);own(a,1);own(b,2);
        EmbeddedChannel ca=new EmbeddedChannel(a),cb=new EmbeddedChannel(b);
        try {
            cb.writeInbound(ProxyMessage.builder().type(ProxyMessage.MessageType.DATA).streamId(1).build());assertEquals(0,ra.packets.get());
            ca.writeInbound(ProxyMessage.builder().type(ProxyMessage.MessageType.DATA).streamId(1).build());assertEquals(1,ra.packets.get());
            a.onDisconnected();assertEquals(1,ra.closed.get());assertEquals(0,rb.closed.get());assertSame(rb,registry.get(2L));
            cb.writeInbound(ProxyMessage.builder().type(ProxyMessage.MessageType.DATA).streamId(2).data(new byte[0]).build());assertEquals(1,rb.packets.get());
        } finally {ca.finishAndReleaseAll();cb.finishAndReleaseAll();b.onDisconnected();}
        assertTrue(registry.isEmpty());
    }
    @Test void lateOldCloseCannotRemoveReplacementReceiver() {
        ExchangeHandler old=new ExchangeHandler(), replacement=new ExchangeHandler();
        ConcurrentHashMap<Long,DatagramReceiver> registry=new ConcurrentHashMap<>();
        Receiver before=new Receiver(),after=new Receiver();registry.put(9L,before);old.setDatagramRegistry(registry);own(old,9);
        registry.put(9L,after);replacement.setDatagramRegistry(registry);own(replacement,9);
        old.onStreamClosed(9,null);assertSame(after,registry.get(9L));assertEquals(1,before.closed.get());assertEquals(0,after.closed.get());
        replacement.onDisconnected();assertTrue(registry.isEmpty());assertEquals(1,after.closed.get());
    }
}
