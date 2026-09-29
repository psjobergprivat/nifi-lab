package org.mylabs.nifilab.mock;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.resteasy.reactive.RestHeader;
import org.mylabs.nifilab.live.LiveHub;
import org.mylabs.nifilab.scenario.ControlStore;
import org.mylabs.nifilab.scenario.ScenarioReset;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A pretend external service that scenario flows call with InvokeHTTP. Its behaviour follows the scenario's
 * controls, so the user can make it slow or failing:
 * <ul>
 *     <li>{@code service.latency-ms} - delay before answering</li>
 *     <li>{@code service.mode} - {@code normal}, {@code down} (always 503) or {@code reject-all} (always 400)</li>
 *     <li>{@code service.failure-rate} - percentage of requests answered with 503</li>
 * </ul>
 * A request whose body contains "invalid" is always rejected with 400.
 */
@ApplicationScoped
@Path("/mock/{scenarioId}/service")
public class MockServiceResource {

    @Inject
    ControlStore controls;

    @Inject
    LiveHub hub;

    /** Calls per scenario and trace id, so retries show up as attempt 2, 3, ... */
    private final Map<String, AtomicInteger> attempts = new ConcurrentHashMap<>();

    public record Call(String traceId, int attempt, int status, String outcome, int latencyMs) {
    }

    @POST
    @Consumes(MediaType.WILDCARD)
    @Produces(MediaType.APPLICATION_JSON)
    public Response call(@PathParam("scenarioId") String scenarioId, @RestHeader("lab.trace.id") String traceId,
                         byte[] body) {
        String request = body == null ? "" : new String(body, StandardCharsets.UTF_8);
        int attempt = attempts.computeIfAbsent(scenarioId + "|" + traceId, k -> new AtomicInteger()).incrementAndGet();
        int latency = controls.intValue(scenarioId, "service.latency-ms", 0);
        String mode = controls.value(scenarioId, "service.mode", "normal");
        int failureRate = controls.intValue(scenarioId, "service.failure-rate", 0);

        sleep(latency);

        int status;
        String outcome;
        if (request.toLowerCase().contains("invalid")) {
            status = 400;
            outcome = "rejected: the request contains 'invalid'";
        } else if ("down".equals(mode)) {
            status = 503;
            outcome = "service is down";
        } else if ("reject-all".equals(mode)) {
            status = 400;
            outcome = "rejected: service rejects everything";
        } else if (ThreadLocalRandom.current().nextInt(100) < failureRate) {
            status = 503;
            outcome = "random failure (" + failureRate + "% failure rate)";
        } else {
            status = 200;
            outcome = "ok";
        }
        hub.publish("service", scenarioId, new Call(traceId, attempt, status, outcome, latency));
        return Response.status(status)
                .entity(Map.of("result", outcome, "attempt", attempt, "traceId", String.valueOf(traceId),
                        "request", request))
                .build();
    }

    void onReset(@Observes ScenarioReset reset) {
        attempts.keySet().removeIf(key -> key.startsWith(reset.scenarioId() + "|"));
    }

    private static void sleep(int millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
