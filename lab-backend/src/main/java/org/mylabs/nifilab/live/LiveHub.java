package org.mylabs.nifilab.live;

import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.operators.multi.processors.BroadcastProcessor;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/** Fan-out of {@link LiveEvent}s to SSE subscribers, and bookkeeping of which scenarios are being watched. */
@ApplicationScoped
public class LiveHub {

    private final BroadcastProcessor<LiveEvent> processor = BroadcastProcessor.create();
    private final Map<String, AtomicInteger> subscribers = new ConcurrentHashMap<>();

    public synchronized void publish(LiveEvent event) {
        processor.onNext(event);
    }

    public void publish(String type, String scenarioId, Object data) {
        publish(LiveEvent.of(type, scenarioId, data));
    }

    public Multi<LiveEvent> events() {
        return processor;
    }

    /** Scenarios with at least one open browser; only these are polled for live status. */
    public Set<String> watchedScenarios() {
        return subscribers.entrySet().stream()
                .filter(e -> e.getValue().get() > 0)
                .map(Map.Entry::getKey)
                .collect(Collectors.toSet());
    }

    void subscribed(String scenarioId) {
        subscribers.computeIfAbsent(scenarioId, k -> new AtomicInteger()).incrementAndGet();
    }

    void unsubscribed(String scenarioId) {
        subscribers.computeIfAbsent(scenarioId, k -> new AtomicInteger()).decrementAndGet();
    }
}
