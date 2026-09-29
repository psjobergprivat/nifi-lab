package org.mylabs.nifilab.live;

import io.smallrye.mutiny.Multi;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.jboss.resteasy.reactive.RestStreamElementType;

import java.time.Duration;

@Path("/api/live")
public class LiveResource {

    @Inject
    LiveHub hub;

    /** Server-Sent Events for one scenario: status snapshots, arriving items, bulletins, ... */
    @GET
    @Path("/{scenarioId}")
    @Produces(MediaType.SERVER_SENT_EVENTS)
    @RestStreamElementType(MediaType.APPLICATION_JSON)
    public Multi<LiveEvent> stream(@PathParam("scenarioId") String scenarioId) {
        // Heartbeats keep proxies from closing idle connections and let the UI detect a dead backend.
        Multi<LiveEvent> heartbeats = Multi.createFrom().ticks().every(Duration.ofSeconds(15))
                .map(tick -> LiveEvent.of("heartbeat", scenarioId, null));
        Multi<LiveEvent> events = hub.events().filter(e -> scenarioId.equals(e.scenarioId()));
        return Multi.createBy().merging().streams(events, heartbeats)
                .onSubscription().invoke(() -> hub.subscribed(scenarioId))
                .onTermination().invoke(() -> hub.unsubscribed(scenarioId));
    }
}
