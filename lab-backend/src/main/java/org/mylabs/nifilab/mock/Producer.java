package org.mylabs.nifilab.mock;

import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.mylabs.nifilab.live.LiveHub;
import org.mylabs.nifilab.scenario.ControlStore;
import org.mylabs.nifilab.scenario.Deployment;
import org.mylabs.nifilab.scenario.ScenarioDefinition;
import org.mylabs.nifilab.scenario.ScenarioRegistry;
import org.mylabs.nifilab.scenario.ScenarioReset;
import org.mylabs.nifilab.scenario.ScenarioService;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Sends a steady stream of events into scenarios that have a {@code producer.rate} control (events per second),
 * and counts how many NiFi accepted and how many it refused with 503 because of back pressure.
 */
@ApplicationScoped
public class Producer {

    static final String RATE = "producer.rate";

    @Inject
    ScenarioRegistry registry;

    @Inject
    ScenarioService scenarios;

    @Inject
    ControlStore controls;

    @Inject
    LiveHub hub;

    private final Map<String, Stats> stats = new ConcurrentHashMap<>();

    /** Totals since the last reset, plus the counts of the last one-second tick. */
    public record Stats(int rate, long sent, long accepted, long refused, long failed,
                        int lastAccepted, int lastRefused, int lastFailed, String lastError) {

        static final Stats EMPTY = new Stats(0, 0, 0, 0, 0, 0, 0, 0, null);
    }

    @Scheduled(every = "1s", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void tick() {
        for (ScenarioDefinition scenario : registry.all()) {
            if (scenario.controls().stream().noneMatch(c -> RATE.equals(c.id()))) {
                continue;
            }
            int rate = controls.intValue(scenario.id(), RATE, 0);
            Deployment deployment = scenarios.deployment(scenario.id());
            if (rate <= 0 || !deployment.deployed() || deployment.runState() != Deployment.RunState.RUNNING) {
                idle(scenario.id());
                continue;
            }
            produce(scenario.id(), rate);
        }
    }

    /** Nothing was sent this second: the last-second counts (and the "pushed back" status) drop to zero. */
    private void idle(String scenarioId) {
        Stats previous = stats.get(scenarioId);
        if (previous == null || (previous.rate() == 0 && previous.lastAccepted() == 0
                && previous.lastRefused() == 0 && previous.lastFailed() == 0)) {
            return;
        }
        Stats updated = new Stats(0, previous.sent(), previous.accepted(), previous.refused(), previous.failed(),
                0, 0, 0, previous.lastError());
        stats.put(scenarioId, updated);
        hub.publish("producer", scenarioId, updated);
    }

    private void produce(String scenarioId, int rate) {
        int accepted = 0;
        int refused = 0;
        int failed = 0;
        String lastError = null;
        long spacingMillis = 900L / rate;
        for (int i = 0; i < rate; i++) {
            String content = "{\"event\": \"" + UUID.randomUUID().toString().substring(0, 8)
                    + "\", \"producedAt\": " + System.currentTimeMillis() + "}";
            try {
                int status = scenarios.post(scenarioId, UUID.randomUUID().toString(), content, null);
                if (status / 100 == 2) {
                    accepted++;
                } else if (status == 503) {
                    refused++;
                } else {
                    failed++;
                    lastError = "HTTP " + status;
                }
            } catch (IOException | RuntimeException e) {
                failed++;
                lastError = e.getClass().getSimpleName() + ": " + e.getMessage();
            }
            sleep(spacingMillis);
        }
        Stats previous = stats.getOrDefault(scenarioId, Stats.EMPTY);
        Stats updated = new Stats(rate, previous.sent() + rate, previous.accepted() + accepted,
                previous.refused() + refused, previous.failed() + failed, accepted, refused, failed, lastError);
        stats.put(scenarioId, updated);
        hub.publish("producer", scenarioId, updated);
    }

    void onReset(@Observes ScenarioReset reset) {
        stats.remove(reset.scenarioId());
        hub.publish("producer", reset.scenarioId(), Stats.EMPTY);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
