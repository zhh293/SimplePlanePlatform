package io.loglens.proxyremote;

import io.loglens.api.TelemetryEvent;
import io.loglens.api.TelemetryKind;
import io.loglens.bootstrap.CaptureBridge;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Predicate;

/** Bounded, payload-free state and counters for active proxy connections and sessions. */
public final class ProxyRemoteMonitor implements CaptureBridge.TelemetryCallback, AutoCloseable {
    private record Connection(String id, String peer, String transport, long openedAt) {}
    private static final class Session {
        final String key, streamId, connectionId, peer, transport, host, deviceId, accountId;
        final int port;
        final long openedAt;
        final LongAdder up = new LongAdder(), down = new LongAdder();
        volatile boolean active;
        Session(String key, String streamId, String connectionId, String peer, String transport,
                String host, int port, long openedAt, String deviceId, String accountId) {
            this.key=key; this.streamId=streamId; this.connectionId=connectionId; this.peer=peer;
            this.transport=transport; this.host=host; this.port=port; this.openedAt=openedAt;
            this.deviceId=deviceId; this.accountId=accountId;
        }
    }
    private final String sessionId;
    private final Predicate<TelemetryEvent> publish;
    private final int maxConnections, maxSessions;
    private final long sampleMillis;
    private final ConcurrentHashMap<String, Connection> connections = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Session> sessions = new ConcurrentHashMap<>();
    private final AtomicLong dropped = new AtomicLong();
    private final AtomicInteger connectionSlots = new AtomicInteger(), sessionSlots = new AtomicInteger();
    private final ScheduledExecutorService sampler;
    private volatile boolean closed;

    public ProxyRemoteMonitor(String sessionId, long sampleMillis, int maxConnections, int maxSessions,
                              Predicate<TelemetryEvent> publish) {
        if (sampleMillis < 100 || sampleMillis > 60000 || maxConnections < 1 || maxSessions < 1)
            throw new IllegalArgumentException("invalid proxy telemetry limits");
        this.sessionId=sessionId; this.sampleMillis=sampleMillis; this.maxConnections=maxConnections;
        this.maxSessions=maxSessions; this.publish=publish;
        sampler=Executors.newSingleThreadScheduledExecutor(task->{Thread t=new Thread(task,"loglens-proxy-sampler");t.setDaemon(true);t.setContextClassLoader(ProxyRemoteMonitor.class.getClassLoader());return t;});
        sampler.scheduleAtFixedRate(this::sample, sampleMillis, sampleMillis, TimeUnit.MILLISECONDS);
    }
    @Override public void connectionOpened(Object channel, String transport) {
        if (closed || channel == null || !isPhysicalConnection(channel, transport)) return;
        String id=channelId(channel), peer=remoteAddress(channel);
        if (id.isBlank()) return;
        Connection value=new Connection(id, peer, transport, System.currentTimeMillis());
        if (connections.containsKey(id)) return;
        if (!reserve(connectionSlots,maxConnections)) { dropped.incrementAndGet(); return; }
        Connection old=connections.putIfAbsent(id,value);
        if (old != null) { connectionSlots.decrementAndGet(); return; }
        emit(TelemetryKind.CLIENT_CONNECTED,id,null,peer,transport,null,0,"connected",null,0,0);
        attachClose(channel,value);
    }
    @Override public void sessionRegistered(Object object) {
        if (closed || object == null) return;
        String key=string(call(object,"getSessionKey"));
        if (key.isBlank()) return;
        Object context=call(object,"getInboundCtx"), streamChannel=call(context,"channel");
        Object parent=call(streamChannel,"parent"); Object physical=parent == null ? streamChannel : parent;
        String connectionId=channelId(physical), peer=remoteAddress(physical), transport=transport(physical);
        String stream=string(call(object,"getRawStreamId"));
        String host=string(call(object,"getTargetHost")); int port=integer(call(object,"getTargetPort"));
        Session s=new Session(key,stream,connectionId,peer,transport,host,port,System.currentTimeMillis(),
                (String) call(object,"getDeviceId"), (String) call(object,"getAccountId"));
        if (sessions.containsKey(key)) return;
        if (!reserve(sessionSlots,maxSessions)) { dropped.incrementAndGet(); return; }
        if (sessions.putIfAbsent(key,s)==null) emitSession(TelemetryKind.SESSION_OPENED,s,"connecting",null);
        else sessionSlots.decrementAndGet();
    }
    @Override public void sessionActive(Object object) {
        Session s=find(object); if(s!=null && !s.active){s.active=true;emitSession(TelemetryKind.SESSION_ACTIVE,s,"active",null);}
    }
    @Override public void sessionClosed(Object object) {
        Session s=find(object); if(s==null || !sessions.remove(s.key,s)) return; sessionSlots.decrementAndGet();
        String status=s.active?"closed":"connect-failed";
        emitSession(s.active?TelemetryKind.SESSION_CLOSED:TelemetryKind.SESSION_CONNECT_FAILED,s,status,
                s.active?"closed":"closed-before-active");
    }
    @Override public void uplink(Object object,int count){Session s=find(object);if(s!=null&&count>0)s.up.add(count);}
    @Override public void downlink(Object object,int count){Session s=find(object);if(s!=null&&count>0)s.down.add(count);}
    private Session find(Object object){String key=string(call(object,"getSessionKey"));return sessions.get(key);}
    private static boolean reserve(AtomicInteger slots,int maximum){for(;;){int current=slots.get();if(current>=maximum)return false;if(slots.compareAndSet(current,current+1))return true;}}
    private void sample(){if(closed)return;for(Session s:sessions.values())emitSession(TelemetryKind.TRAFFIC_SAMPLE,s,s.active?"active":"connecting",null);}
    private void emitSession(TelemetryKind kind, Session s, String status, String reason) {
        long now = System.currentTimeMillis();
        emit(new TelemetryEvent(null, sessionId, 0, now, now, Math.max(0L, System.nanoTime()),
                kind, s.connectionId, s.streamId, s.peer, s.transport, s.host, Math.max(0, s.port), status,
                reason, s.up.sum(), s.down.sum(), s.deviceId, s.accountId, s.key));
    }
    private void emit(TelemetryKind kind,String connection,String stream,String peer,String transport,String host,int port,String status,String reason,long up,long down){
        long now=System.currentTimeMillis(); TelemetryEvent event=new TelemetryEvent(UUID.randomUUID().toString(),sessionId,0,now,now,Math.max(0L,System.nanoTime()),kind,connection,stream,peer,transport,host,Math.max(0,port),status,reason,up,down);
        emit(event);
    }
    private void emit(TelemetryEvent event) {
        try{if(!publish.test(event))dropped.incrementAndGet();}catch(Throwable ignored){dropped.incrementAndGet();}
    }
    public List<TelemetryEvent> activeSnapshot(){List<TelemetryEvent> out=new ArrayList<>();for(Connection c:connections.values())out.add(new TelemetryEvent(UUID.randomUUID().toString(),sessionId,0,c.openedAt(),System.currentTimeMillis(),Math.max(0L,System.nanoTime()),TelemetryKind.CLIENT_CONNECTED,c.id(),null,c.peer(),c.transport(),null,0,"connected",null,0,0));for(Session s:sessions.values())out.add(new TelemetryEvent(UUID.randomUUID().toString(),sessionId,0,s.openedAt,System.currentTimeMillis(),Math.max(0L,System.nanoTime()),TelemetryKind.TRAFFIC_SAMPLE,s.connectionId,s.streamId,s.peer,s.transport,s.host,s.port,s.active?"active":"connecting",null,s.up.sum(),s.down.sum()));return List.copyOf(out);}
    public long dropped(){return dropped.get();}
    public int connectionCount(){return connections.size();}
    public int sessionCount(){return sessions.size();}
    private void attachClose(Object channel,Connection c){
        try{
            Object future=call(channel,"closeFuture");ClassLoader loader=channel.getClass().getClassLoader();
            Class<?> listener=Class.forName("io.netty.util.concurrent.GenericFutureListener",false,loader);
            Object proxy=Proxy.newProxyInstance(loader,new Class<?>[]{listener},(p,m,args)->{if(m.getName().equals("operationComplete")){connectionClosed(c);return null;}if(m.getName().equals("toString"))return "LogLensTelemetryCloseListener";return null;});
            Method add=future.getClass().getMethod("addListener",listener);add.invoke(future,proxy);
        }catch(Throwable ignored){dropped.incrementAndGet();}
    }
    private void connectionClosed(Connection c){if(connections.remove(c.id(),c)){connectionSlots.decrementAndGet();emit(TelemetryKind.CLIENT_DISCONNECTED,c.id(),null,c.peer(),c.transport(),null,0,"disconnected","transport-closed",0,0);}}
    private static boolean isPhysicalConnection(Object channel,String transport){
        String name=channel.getClass().getName();
        if("http3".equals(transport))return name.contains("QuicChannel")&&!name.contains("QuicStreamChannel");
        Object pipeline=call(channel,"pipeline");Object names=call(pipeline,"names");return names instanceof Iterable<?> it && contains(it,"frameCodec");
    }
    private static boolean contains(Iterable<?> values,String expected){for(Object v:values)if(expected.equals(String.valueOf(v)))return true;return false;}
    private static String transport(Object channel){String name=channel==null?"":channel.getClass().getName();return name.contains("Quic")?"http3":"http2";}
    private static String channelId(Object channel){Object id=call(channel,"id");Object text=call(id,"asLongText");return string(text);}
    private static String remoteAddress(Object channel){Object address=call(channel,"remoteAddress");return address==null?"":String.valueOf(address);}
    private static Object call(Object target,String name){
        if(target==null)return null;
        try{Method method=publicMethod(target.getClass(),name);return method==null?null:method.invoke(target);}
        catch(Throwable ignored){return null;}
    }
    private static Method publicMethod(Class<?> type,String name){
        if(type==null)return null;
        if(Modifier.isPublic(type.getModifiers()))try{return type.getMethod(name);}catch(NoSuchMethodException ignored){}
        for(Class<?> contract:type.getInterfaces()){Method method=publicMethod(contract,name);if(method!=null)return method;}
        return publicMethod(type.getSuperclass(),name);
    }
    private static String string(Object value){return value==null?"":String.valueOf(value);}
    private static int integer(Object value){return value instanceof Number n?n.intValue():0;}
    @Override public void close(){closed=true;sampler.shutdownNow();for(Session s:sessions.values())sessionClosedByShutdown(s);sessions.clear();connections.clear();sessionSlots.set(0);connectionSlots.set(0);}
    private void sessionClosedByShutdown(Session s){emitSession(TelemetryKind.SESSION_CLOSED,s,"agent-shutdown","agent-shutdown");}
}
