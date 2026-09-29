package org.mylabs.nifilab.nifi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.jboss.logging.Logger;
import org.mylabs.nifilab.nifi.NifiModel.Bulletin;
import org.mylabs.nifilab.nifi.NifiModel.FlowGraph;
import org.mylabs.nifilab.nifi.NifiModel.FlowStatus;
import org.mylabs.nifilab.nifi.NifiModel.GroupRef;
import org.mylabs.nifilab.nifi.NifiModel.Lineage;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/** High-level NiFi operations used by the scenarios. All methods are blocking. */
@ApplicationScoped
public class NifiService {

    private static final Logger LOG = Logger.getLogger(NifiService.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    @Inject
    @RestClient
    NifiApi api;

    @Inject
    NifiAuth auth;

    private static final int MAX_LINEAGE_ROUNDS = 10;

    private final String clientId = "nifi-lab-" + UUID.randomUUID();

    public String version() {
        return call(api::about).path("about").path("version").asText();
    }

    public List<GroupRef> rootChildGroups() {
        return NifiMapper.childGroups(call(() -> api.processGroupFlow("root")));
    }

    /** Imports a flow definition as a new process group on the root canvas. */
    public GroupRef importFlow(String name, String comments, double x, double y, byte[] flowDefinition) {
        JsonNode created = call(() -> api.uploadFlowDefinition("root", name, x, y, clientId, flowDefinition));
        String id = created.path("id").asText();

        ObjectNode update = JSON.createObjectNode();
        update.putObject("revision")
                .put("version", created.path("revision").path("version").asLong())
                .put("clientId", clientId);
        update.putObject("component").put("id", id).put("comments", comments);
        JsonNode updated = call(() -> api.updateProcessGroup(id, update));
        return new GroupRef(id, name, comments, updated.path("revision").path("version").asLong());
    }

    public FlowGraph graph(String groupId) {
        return NifiMapper.graph(call(() -> api.processGroupFlow(groupId)));
    }

    public FlowStatus status(String groupId) {
        return NifiMapper.status(call(() -> api.processGroupStatus(groupId, true)));
    }

    public List<Bulletin> bulletins(String groupId, Long afterId) {
        return NifiMapper.bulletins(call(() -> api.bulletinBoard(groupId, afterId, 50)));
    }

    /**
     * Provenance lineage of one FlowFile (by uuid), all the way back to where its data entered NiFi. NiFi computes
     * lineage per FlowFile, so events that had parents (JOIN, FORK, CLONE) are expanded with PARENTS requests until
     * every ancestor is included.
     */
    public Lineage lineage(String flowFileUuid) {
        List<JsonNode> lineages = new ArrayList<>();
        lineages.add(computeLineage(lineageRequest("FLOWFILE").put("uuid", flowFileUuid)));
        Set<String> expanded = new HashSet<>();
        for (int round = 0; round < MAX_LINEAGE_ROUNDS; round++) {
            List<String> toExpand = NifiMapper.eventsWithParents(lineages).stream()
                    .filter(expanded::add)
                    .limit(20)
                    .toList();
            if (toExpand.isEmpty()) {
                break;
            }
            for (String eventId : toExpand) {
                lineages.add(computeLineage(lineageRequest("PARENTS").put("eventId", Long.parseLong(eventId))));
            }
        }
        Map<String, JsonNode> details = new HashMap<>();
        return NifiMapper.lineage(flowFileUuid, lineages,
                id -> details.computeIfAbsent(id, key -> call(() -> api.provenanceEvent(key))));
    }

    private static ObjectNode lineageRequest(String type) {
        ObjectNode body = JSON.createObjectNode();
        return body.putObject("lineage").putObject("request").put("lineageRequestType", type);
    }

    /** Submits a lineage request (built by {@link #lineageRequest}), waits for it and deletes it. */
    private JsonNode computeLineage(ObjectNode request) {
        ObjectNode body = JSON.createObjectNode();
        body.putObject("lineage").set("request", request);
        String requestId = call(() -> api.submitLineage(body)).path("lineage").path("id").asText();
        AtomicReference<JsonNode> result = new AtomicReference<>();
        try {
            waitUntil(Duration.ofSeconds(20), () -> {
                result.set(call(() -> api.lineage(requestId)));
                return result.get().path("lineage").path("finished").asBoolean();
            });
        } finally {
            call(() -> api.deleteLineage(requestId));
        }
        return result.get();
    }

    public void start(String groupId) {
        call(() -> api.activateControllerServices(groupId, stateRequest(groupId, "ENABLED")));
        call(() -> api.scheduleComponents(groupId, stateRequest(groupId, "RUNNING")));
    }

    public void stop(String groupId) {
        call(() -> api.scheduleComponents(groupId, stateRequest(groupId, "STOPPED")));
    }

    /** Drops every FlowFile queued anywhere in the group and waits until NiFi reports it done. */
    public void emptyQueues(String groupId) {
        String requestId = call(() -> api.createEmptyQueuesRequest(groupId)).path("dropRequest").path("id").asText();
        try {
            waitUntil(Duration.ofSeconds(30), () ->
                    call(() -> api.emptyQueuesRequest(groupId, requestId)).path("dropRequest").path("finished").asBoolean());
        } finally {
            call(() -> api.deleteEmptyQueuesRequest(groupId, requestId));
        }
    }

    /** Clears local/cluster state of the given (stopped) processors; processors without state are skipped. */
    public void clearState(Collection<String> processorIds) {
        for (String id : processorIds) {
            try {
                call(() -> api.clearProcessorState(id));
            } catch (NifiException e) {
                LOG.debugf("Could not clear state of processor %s: %s", id, e.getMessage());
            }
        }
    }

    /** Stops, drains and deletes a process group. Stopping is asynchronous in NiFi, so deletion is retried. */
    public void delete(String groupId) {
        stop(groupId);
        call(() -> api.activateControllerServices(groupId, stateRequest(groupId, "DISABLED")));
        emptyQueues(groupId);
        waitUntil(Duration.ofSeconds(30), () -> {
            try {
                long version = call(() -> api.processGroup(groupId)).path("revision").path("version").asLong();
                call(() -> api.deleteProcessGroup(groupId, version, clientId));
                return true;
            } catch (NifiException e) {
                if (e.status() == 409) {
                    return false; // components still stopping / services still disabling
                }
                throw e;
            }
        });
    }

    private ObjectNode stateRequest(String groupId, String state) {
        return JSON.createObjectNode().put("id", groupId).put("state", state);
    }

    /** Runs a NiFi call, re-authenticating once on 401 and turning HTTP errors into {@link NifiException}. */
    private <T> T call(Supplier<T> request) {
        try {
            try {
                return request.get();
            } catch (WebApplicationException e) {
                if (e.getResponse().getStatus() != 401) {
                    throw e;
                }
                auth.invalidate();
                return request.get();
            }
        } catch (WebApplicationException e) {
            throw NifiException.from(e);
        } catch (RuntimeException e) {
            throw new NifiException(0, "NiFi is not reachable: " + rootMessage(e), e);
        }
    }

    private static void waitUntil(Duration timeout, Supplier<Boolean> condition) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!condition.get()) {
            if (System.nanoTime() > deadline) {
                throw new NifiException(0, "Timed out after " + timeout.toSeconds() + "s waiting for NiFi", null);
            }
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new NifiException(0, "Interrupted while waiting for NiFi", e);
            }
        }
    }

    private static String rootMessage(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root.getMessage() != null ? root.getMessage() : root.getClass().getSimpleName();
    }
}
