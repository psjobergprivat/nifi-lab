package org.mylabs.nifilab.scenario;

import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;
import org.mylabs.nifilab.LabConfig;
import org.mylabs.nifilab.live.LiveHub;
import org.mylabs.nifilab.nifi.NifiException;
import org.mylabs.nifilab.nifi.NifiModel.FlowNode;
import org.mylabs.nifilab.nifi.NifiModel.FlowStatus;
import org.mylabs.nifilab.nifi.NifiModel.GroupRef;
import org.mylabs.nifilab.nifi.NifiService;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Deploys scenario flows into NiFi and runs them: start, stop, reset and feeding input. */
@ApplicationScoped
public class ScenarioService {

    private static final Logger LOG = Logger.getLogger(ScenarioService.class);
    static final String MARKER = "nifi-lab:scenario=";

    @Inject
    LabConfig config;

    @Inject
    ScenarioRegistry registry;

    @Inject
    NifiService nifi;

    @Inject
    LiveHub hub;

    private final Map<String, Deployment> deployments = new ConcurrentHashMap<>();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    public Deployment deployment(String scenarioId) {
        return deployments.computeIfAbsent(scenarioId, Deployment::pending);
    }

    /** Deploys whatever is not deployed yet. Runs periodically, so it also covers NiFi starting after us. */
    @Scheduled(every = "10s", delayed = "1s", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void ensureDeployed() {
        if (!config.deployOnStartup()) {
            return;
        }
        for (ScenarioDefinition scenario : registry.all()) {
            Deployment current = deployment(scenario.id());
            if (current.deployed()) {
                continue;
            }
            try {
                deploy(scenario);
            } catch (NifiException e) {
                if (e.status() == 0) {
                    LOG.debugf("NiFi not reachable yet: %s", e.getMessage());
                    return; // no point trying the other scenarios
                }
                LOG.warnf("Deploying scenario %s failed: %s", scenario.id(), e.getMessage());
            }
        }
    }

    /**
     * Makes sure the scenario's process group exists and matches its flow.json. The group is recognised by a
     * marker in its comments that includes a hash of flow.json; if flow.json changed, the group is replaced.
     */
    public synchronized Deployment deploy(ScenarioDefinition scenario) {
        update(deployment(scenario.id()).with(Deployment.State.DEPLOYING, "Deploying to NiFi"));
        try {
            String hash = sha256(scenario.flowDefinition());
            String marker = MARKER + scenario.id() + ";sha256=" + hash;

            List<GroupRef> existing = nifi.rootChildGroups().stream()
                    .filter(g -> g.comments().startsWith(MARKER + scenario.id() + ";"))
                    .toList();
            GroupRef group = existing.stream()
                    .filter(g -> g.comments().startsWith(marker) && !config.forceRedeploy())
                    .findFirst()
                    .orElse(null);
            if (group == null) {
                for (GroupRef stale : existing) {
                    LOG.infof("Replacing process group '%s' (%s) - %s", stale.name(), stale.id(),
                            config.forceRedeploy() ? "lab.force-redeploy is set" : "flow.json changed");
                    nifi.delete(stale.id());
                }
                int index = registry.all().indexOf(scenario);
                group = nifi.importFlow(scenario.title(),
                        marker + "\nManaged by NiFi Lab. It is re-imported when the scenario's flow.json changes.",
                        (index % 4) * 450.0, (index / 4) * 250.0,
                        scenario.flowDefinition());
                LOG.infof("Deployed scenario %s as process group %s", scenario.id(), group.id());
            }

            Map<String, FlowNode> components = new LinkedHashMap<>();
            nifi.graph(group.id()).nodes().forEach(n -> components.put(n.name(), n));
            Deployment deployed = new Deployment(scenario.id(), Deployment.State.DEPLOYED, group.id(), components,
                    runState(nifi.status(group.id())), "Deployed");
            return update(deployed);
        } catch (NifiException e) {
            update(deployment(scenario.id()).with(e.status() == 0 ? Deployment.State.PENDING : Deployment.State.FAILED,
                    e.getMessage()));
            throw e;
        }
    }

    public Deployment start(String scenarioId) {
        Deployment d = requireDeployed(scenarioId);
        nifi.start(d.groupId());
        return update(d.with(Deployment.RunState.RUNNING));
    }

    public Deployment stop(String scenarioId) {
        Deployment d = requireDeployed(scenarioId);
        nifi.stop(d.groupId());
        return update(d.with(Deployment.RunState.STOPPED));
    }

    /** Back to a clean state: stopped, queues empty, processor state cleared. */
    public Deployment reset(String scenarioId) {
        Deployment d = requireDeployed(scenarioId);
        nifi.stop(d.groupId());
        nifi.emptyQueues(d.groupId());
        nifi.clearState(d.processorIds());
        hub.publish("reset", scenarioId, null);
        return update(d.with(Deployment.RunState.STOPPED));
    }

    /**
     * Posts user input to the scenario's ListenHTTP processor. Lab attributes travel as HTTP headers, which
     * ListenHTTP turns into FlowFile attributes: a trace id to follow the item, and where to send results.
     *
     * @return the trace id
     */
    public String sendInput(String scenarioId, String content) {
        ScenarioDefinition scenario = registry.find(scenarioId).orElseThrow(() -> new ScenarioException(404,
                "Unknown scenario " + scenarioId));
        requireDeployed(scenarioId);
        ScenarioDefinition.Input input = scenario.input();
        String traceId = UUID.randomUUID().toString();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://" + config.nifi().listenHost() + ":" + input.port() + "/" + input.path()))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", input.contentType() != null ? input.contentType() : "text/plain; charset=UTF-8")
                .header("lab.trace.id", traceId)
                .header("lab.scenario", scenarioId)
                .header("lab.sink.url", sinkUrl(scenarioId))
                .POST(HttpRequest.BodyPublishers.ofString(content, StandardCharsets.UTF_8))
                .build();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new ScenarioException(502, "NiFi rejected the input (" + response.statusCode() + "): " + response.body());
            }
        } catch (ConnectException e) {
            throw new ScenarioException(409, "Nothing is listening on port " + input.port()
                    + " - start the scenario first (the ListenHTTP processor only listens while running).");
        } catch (IOException e) {
            throw new ScenarioException(502, "Could not send input to NiFi: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ScenarioException(503, "Interrupted");
        }
        hub.publish("input", scenarioId, Map.of("traceId", traceId, "content", content));
        return traceId;
    }

    String sinkUrl(String scenarioId) {
        String base = config.sinkBaseUrl();
        return (base.endsWith("/") ? base.substring(0, base.length() - 1) : base) + "/api/sink/" + scenarioId;
    }

    /** Keeps the cached run state in sync with what the live poller sees (it may be changed in the NiFi UI). */
    public void observed(String scenarioId, FlowStatus status) {
        Deployment d = deployment(scenarioId);
        Deployment.RunState runState = runState(status);
        if (d.deployed() && d.runState() != runState) {
            update(d.with(runState));
        }
    }

    static Deployment.RunState runState(FlowStatus status) {
        return status.processors().stream().anyMatch(p -> "Running".equalsIgnoreCase(p.runStatus()))
                ? Deployment.RunState.RUNNING : Deployment.RunState.STOPPED;
    }

    private Deployment requireDeployed(String scenarioId) {
        Deployment d = deployment(scenarioId);
        if (!d.deployed()) {
            throw new ScenarioException(409, "Scenario " + scenarioId + " is not deployed to NiFi yet: " + d.message());
        }
        return d;
    }

    private Deployment update(Deployment deployment) {
        Deployment previous = deployments.put(deployment.scenarioId(), deployment);
        if (!deployment.equals(previous)) {
            hub.publish("deployment", deployment.scenarioId(), deployment);
        }
        return deployment;
    }

    static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
