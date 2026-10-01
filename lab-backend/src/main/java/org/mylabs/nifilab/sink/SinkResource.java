package org.mylabs.nifilab.sink;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.core.MediaType;
import org.jboss.logging.Logger;
import org.jboss.resteasy.reactive.RestHeader;
import org.mylabs.nifilab.live.LiveHub;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.TreeMap;

/**
 * Where scenario flows deliver their results (InvokeHTTP → {@code POST /api/sink/<scenario>/<lane>}).
 * The FlowFile content is the body; its attributes arrive as base64-encoded JSON in the {@code lab-attributes}
 * header (built with AttributesToJSON + {@code ${JSONAttributes:base64Encode()}}).
 */
@Path("/api/sink")
public class SinkResource {

    private static final Logger LOG = Logger.getLogger(SinkResource.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    @Inject
    LiveHub hub;

    @Inject
    MergedTraceResolver mergedTraces;

    public record SinkItem(String traceId, String lane, String content, Map<String, String> attributes,
                           long receivedAt) {
    }

    @POST
    @Path("/{scenarioId}/{lane}")
    @Consumes(MediaType.WILDCARD)
    public void receive(@PathParam("scenarioId") String scenarioId, @PathParam("lane") String lane,
                        @RestHeader("lab-attributes") String encodedAttributes, byte[] body) {
        Map<String, String> attributes = decodeAttributes(encodedAttributes);
        SinkItem item = new SinkItem(attributes.get("lab.trace.id"), lane,
                body == null ? "" : new String(body, StandardCharsets.UTF_8), attributes, System.currentTimeMillis());
        hub.publish("item", scenarioId, item);
        if (item.traceId() == null && attributes.containsKey("merge.count") && attributes.containsKey("uuid")) {
            mergedTraces.resolveLater(scenarioId, attributes.get("uuid"));
        }
    }

    static Map<String, String> decodeAttributes(String encoded) {
        if (encoded == null || encoded.isBlank()) {
            return Map.of();
        }
        try {
            byte[] json = Base64.getDecoder().decode(encoded.strip());
            return new TreeMap<>(JSON.readValue(json, new TypeReference<Map<String, String>>() {
            }));
        } catch (Exception e) {
            LOG.warnf("Could not decode lab-attributes header: %s", e.getMessage());
            return Map.of();
        }
    }
}
