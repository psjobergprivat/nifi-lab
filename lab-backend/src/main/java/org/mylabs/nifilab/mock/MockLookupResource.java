package org.mylabs.nifilab.mock;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.mylabs.nifilab.live.LiveHub;
import org.mylabs.nifilab.scenario.LookupStore;

import java.util.Map;
import java.util.Optional;

/**
 * Answers NiFi's RestLookupService from the scenario's editable lookup table. A known key returns its row as a
 * JSON record; an unknown key returns 404 with an empty body, which NiFi treats as "no match".
 */
@Path("/mock/{scenarioId}/lookup/{key}")
public class MockLookupResource {

    @Inject
    LookupStore lookups;

    @Inject
    LiveHub hub;

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public Response lookup(@PathParam("scenarioId") String scenarioId, @PathParam("key") String key) {
        Optional<Map<String, String>> row = lookups.find(scenarioId, key);
        hub.publish("service", scenarioId, new MockServiceResource.Call(null, 1, row.isPresent() ? 200 : 404,
                "lookup " + key + (row.isPresent() ? " -> " + row.get() : " -> not found"), 0));
        return row.map(r -> Response.ok(r).build()).orElseGet(() -> Response.status(404).build());
    }
}
