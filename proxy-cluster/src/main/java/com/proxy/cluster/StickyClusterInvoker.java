package com.proxy.cluster;

import com.proxy.common.cluster.ClusterInvoker;
import com.proxy.common.cluster.LoadBalance;
import com.proxy.common.filter.Invocation;
import com.proxy.common.filter.Invoker;
import com.proxy.common.filter.Response;
import com.proxy.common.model.ProxyMessage;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/** Dispatches to the controller-installed current connection. Stream lifecycle belongs to its client. */
public final class StickyClusterInvoker implements ClusterInvoker {
    private volatile List<Invoker> invokers = Collections.emptyList();
    private volatile LoadBalance loadBalance;
    private volatile Invoker currentInvoker;

    @Override
    public CompletableFuture<Response> invoke(Invocation invocation) {
        Object id = invocation.getAttachment("streamId");
        if (id == null) return error("Missing streamId");
        final long streamId;
        try { streamId = Long.parseLong(id.toString()); }
        catch (NumberFormatException invalid) { return error("Invalid streamId"); }
        if (streamId == 0) return error("Invalid streamId");
        if (invocation.getType() != ProxyMessage.MessageType.CONNECT
                && invocation.getType() != ProxyMessage.MessageType.DATA
                && invocation.getType() != ProxyMessage.MessageType.DISCONNECT) {
            return error("Unsupported message type");
        }
        // Admission and dispatch use the same lock as current replacement. Stream ownership,
        // cancellation and closure belong to the exchange/transport layer, not this cluster.
        synchronized (this) {
            Invoker selected = currentInvoker;
            if (selected == null || !invokers.contains(selected) || !selected.isAvailable()) {
                return error("Current node unavailable");
            }
            try {
                return selected.invoke(invocation);
            } catch (RuntimeException failure) {
                return failed(failure);
            }
        }
    }

    /** Null pauses business dispatch while the controller closes the old client. */
    public synchronized void setCurrentInvoker(Invoker invoker) {
        if (invoker != null && !invokers.contains(invoker)) {
            throw new IllegalArgumentException("Current invoker must be a cluster member");
        }
        currentInvoker = invoker;
    }

    public Invoker getCurrentInvoker() { return currentInvoker; }

    /** Called once per selection, never for each business stream. Candidates are health-filtered by the caller. */
    public synchronized Invoker selectCurrent(List<Invoker> candidates, Invoker preferred) {
        List<Invoker> eligible = new ArrayList<>();
        for (Invoker candidate : candidates) {
            if (invokers.contains(candidate) && candidate.isAvailable()) eligible.add(candidate);
        }
        Invoker selected = eligible.contains(preferred) ? preferred : eligible.isEmpty() ? null
                : loadBalance == null ? eligible.get(java.util.concurrent.ThreadLocalRandom.current().nextInt(eligible.size()))
                : loadBalance.select(eligible, null, Collections.emptySet());
        setCurrentInvoker(selected);
        return selected;
    }

    private static CompletableFuture<Response> error(String message) {
        return CompletableFuture.completedFuture(Response.error(message));
    }

    private static CompletableFuture<Response> failed(Throwable cause) {
        CompletableFuture<Response> result = new CompletableFuture<>();
        result.completeExceptionally(cause);
        return result;
    }

    @Override
    public synchronized void setInvokers(List<Invoker> values) {
        invokers = Collections.unmodifiableList(new ArrayList<>(values));
    }

    @Override
    public synchronized void addInvoker(Invoker invoker) {
        List<Invoker> copy = new ArrayList<>(invokers);
        copy.add(invoker);
        setInvokers(copy);
    }

    @Override
    public synchronized void removeInvoker(Invoker invoker) {
        List<Invoker> copy = new ArrayList<>(invokers);
        copy.remove(invoker);
        setInvokers(copy);
    }

    @Override
    public void setLoadBalance(LoadBalance value) { loadBalance = value; }

    @Override
    public List<Invoker> getAvailableInvokers() {
        List<Invoker> result = new ArrayList<>();
        for (Invoker invoker : invokers) if (invoker.isAvailable()) result.add(invoker);
        return result;
    }

    @Override
    public boolean isAvailable() {
        Invoker selected = currentInvoker;
        return selected != null && invokers.contains(selected) && selected.isAvailable();
    }

    @Override
    public int getActiveCount() {
        Invoker selected = currentInvoker;
        return selected == null ? 0 : selected.getActiveCount();
    }
}
