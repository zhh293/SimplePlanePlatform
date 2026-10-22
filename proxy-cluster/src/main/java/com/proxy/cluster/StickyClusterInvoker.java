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
import java.util.concurrent.ConcurrentHashMap;

/** Selects a concrete connection only at CONNECT. Never retries or replays DATA. */
public final class StickyClusterInvoker implements ClusterInvoker {
    private volatile List<Invoker> invokers = Collections.emptyList();
    private volatile LoadBalance loadBalance;
    private volatile Invoker currentInvoker;
    private volatile boolean fixedSelection;
    private final ConcurrentHashMap<Long, Binding> bindings = new ConcurrentHashMap<>();

    private static final class Binding {
        final Invoker invoker;
        final String targetHost;
        final int targetPort;
        volatile boolean active;
        Binding(Invoker invoker, Invocation connect) {
            this.invoker = invoker;
            this.targetHost = connect.getTargetHost();
            this.targetPort = connect.getTargetPort();
        }
    }

    @Override
    public CompletableFuture<Response> invoke(Invocation invocation) {
        Object id = invocation.getAttachment("streamId");
        if (id == null) return error("Missing streamId");
        final long streamId;
        try { streamId = Long.parseLong(id.toString()); }
        catch (NumberFormatException invalid) { return error("Invalid streamId"); }
        if (streamId == 0) return error("Invalid streamId");
        if (invocation.getType() == ProxyMessage.MessageType.CONNECT) {
            synchronized (this) {
                List<Invoker> available = getAvailableInvokers();
                if (available.isEmpty()) return error("No available invoker");
                Invoker selected = fixedSelection ? currentInvoker : loadBalance == null ? available.get(0)
                        : loadBalance.select(available, invocation, Collections.emptySet());
                if (selected == null || !available.contains(selected)) return error("Current node unavailable");
                Binding binding = new Binding(selected, invocation);
                if (bindings.putIfAbsent(streamId, binding) != null) return error("Stream already bound");
                CompletableFuture<Response> result;
                try {
                    result = selected.invoke(invocation);
                } catch (RuntimeException failure) {
                    bindings.remove(streamId, binding);
                    return failed(failure);
                }
                return result.thenApply(response -> {
                    if (response != null && response.isSuccess() && bindings.get(streamId) == binding) {
                        binding.active = true;
                        return response;
                    }
                    bindings.remove(streamId, binding);
                    return response != null && !response.isSuccess() ? response : Response.error("Stream cancelled");
                }).whenComplete((response, failure) -> {
                    if (failure != null) bindings.remove(streamId, binding);
                });
            }
        }
        Binding binding = bindings.get(streamId);
        if (binding == null) return error("Stream is not bound");
        if (invocation.getType() == ProxyMessage.MessageType.DISCONNECT) {
            // Remove before invoking: a late CONNECT completion cannot resurrect this binding.
            bindings.remove(streamId, binding);
        } else if (invocation.getType() != ProxyMessage.MessageType.DATA || !binding.active) {
            return error("Stream is not active");
        }
        try {
            return binding.invoker.invoke(invocation);
        } catch (RuntimeException failure) {
            return failed(failure);
        }
    }

    /** Called by the stream lifecycle owner after transport failure or browser cancellation. */
    public void unbind(long streamId) { bindings.remove(streamId); }

    /** Stop all old bindings before retiring their transports; cleanup uses the original concrete invoker. */
    public CompletableFuture<Void> retireBindings() {
        List<CompletableFuture<Response>> disconnects = new ArrayList<>();
        bindings.forEach((streamId, binding) -> {
            if (!bindings.remove(streamId, binding)) return;
            binding.active = false;
            Invocation disconnect = new Invocation(binding.targetHost, binding.targetPort, null,
                    ProxyMessage.MessageType.DISCONNECT);
            disconnect.setAttachment("streamId", streamId);
            try { disconnects.add(binding.invoker.invoke(disconnect)); }
            catch (RuntimeException ignored) { /* Transport closure remains the final cleanup boundary. */ }
        });
        return CompletableFuture.allOf(disconnects.toArray(new CompletableFuture<?>[0]));
    }

    public int getBindingCount() { return bindings.size(); }

    /** Null pauses new CONNECT admission without discarding preconnected members. */
    public synchronized void setCurrentInvoker(Invoker invoker) {
        if (invoker != null && !invokers.contains(invoker)) {
            throw new IllegalArgumentException("Current invoker must be a cluster member");
        }
        fixedSelection = true;
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
        return fixedSelection ? currentInvoker != null && invokers.contains(currentInvoker) && currentInvoker.isAvailable()
                : !getAvailableInvokers().isEmpty();
    }

    @Override
    public int getActiveCount() { return bindings.size(); }
}
