package org.mylabs.nifilab.scenario;

import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.mylabs.nifilab.nifi.NifiModel.FlowEdge;
import org.mylabs.nifilab.nifi.NifiModel.FlowGraph;
import org.mylabs.nifilab.nifi.NifiModel.FlowNode;
import org.mylabs.nifilab.nifi.NifiService;

import java.util.List;
import java.util.Map;

@Path("/api/scenarios")
@Produces(MediaType.APPLICATION_JSON)
public class ScenarioResource {

    @Inject
    ScenarioRegistry registry;

    @Inject
    ScenarioService service;

    @Inject
    NifiService nifi;

    @Inject
    DeepLinks links;

    @Inject
    ControlStore controlStore;

    public record ScenarioSummary(String id, int order, String title, String summary, List<String> learn,
                                  DeploymentView deployment) {
    }

    public record ScenarioDetail(String id, int order, String title, String summary, List<String> learn,
                                 ScenarioDefinition.Input input, List<ScenarioDefinition.Sample> samples,
                                 List<ScenarioDefinition.Lane> lanes, List<StepView> steps,
                                 List<ScenarioDefinition.Control> controls, Map<String, String> controlValues,
                                 DeploymentView deployment, Map<String, String> links) {
    }

    public record StepView(String title, String text, String component, String nifiLink) {
    }

    public record DeploymentView(String state, String runState, String message, String groupId, String nifiLink) {
    }

    public record FlowView(String groupId, List<NodeView> nodes, List<EdgeView> edges) {
    }

    public record NodeView(String id, String kind, String name, String type, double x, double y, String nifiLink) {
    }

    public record EdgeView(String id, String sourceId, String destinationId, List<String> relationships,
                           String nifiLink) {
    }

    public record InputResult(String traceId) {
    }

    @GET
    public List<ScenarioSummary> list() {
        return registry.all().stream()
                .map(s -> new ScenarioSummary(s.id(), s.order(), s.title(), s.summary(), s.learn(),
                        view(service.deployment(s.id()))))
                .toList();
    }

    @GET
    @Path("/{id}")
    public ScenarioDetail get(@PathParam("id") String id) {
        ScenarioDefinition s = scenario(id);
        Deployment d = service.deployment(id);
        List<StepView> steps = s.steps().stream()
                .map(step -> new StepView(step.title(), step.text(), step.component(),
                        step.component() == null || !d.deployed() ? null
                                : d.component(step.component()).map(n -> links.component(d.groupId(), n)).orElse(null)))
                .toList();
        return new ScenarioDetail(s.id(), s.order(), s.title(), s.summary(), s.learn(), s.input(), s.samples(),
                s.lanes(), steps, s.controls(), controlStore.values(id), view(d),
                Map.of("provenance", links.provenance(), "bulletins", links.bulletins()));
    }

    /** The scenario's flow as NiFi currently has it, for drawing the mini flow diagram. */
    @GET
    @Path("/{id}/flow")
    public FlowView flow(@PathParam("id") String id) {
        scenario(id);
        Deployment d = service.deployment(id);
        if (!d.deployed()) {
            throw new ScenarioException(409, "Scenario " + id + " is not deployed to NiFi yet: " + d.message());
        }
        FlowGraph graph = nifi.graph(d.groupId());
        List<NodeView> nodes = graph.nodes().stream()
                .map((FlowNode n) -> new NodeView(n.id(), n.kind(), n.name(), n.type(), n.x(), n.y(),
                        links.component(d.groupId(), n)))
                .toList();
        List<EdgeView> edges = graph.edges().stream()
                .map((FlowEdge e) -> new EdgeView(e.id(), e.sourceId(), e.destinationId(), e.relationships(),
                        links.connection(d.groupId(), e.id())))
                .toList();
        return new FlowView(graph.groupId(), nodes, edges);
    }

    @POST
    @Path("/{id}/deploy")
    public DeploymentView deploy(@PathParam("id") String id) {
        return view(service.deploy(scenario(id)));
    }

    @POST
    @Path("/{id}/start")
    public DeploymentView start(@PathParam("id") String id) {
        scenario(id);
        return view(service.start(id));
    }

    @POST
    @Path("/{id}/stop")
    public DeploymentView stop(@PathParam("id") String id) {
        scenario(id);
        return view(service.stop(id));
    }

    @POST
    @Path("/{id}/reset")
    public DeploymentView reset(@PathParam("id") String id) {
        scenario(id);
        return view(service.reset(id));
    }

    @POST
    @Path("/{id}/input")
    @Consumes(MediaType.TEXT_PLAIN)
    public InputResult input(@PathParam("id") String id, String content) {
        scenario(id);
        return new InputResult(service.sendInput(id, content == null ? "" : content));
    }

    @PUT
    @Path("/{id}/controls")
    @Consumes(MediaType.APPLICATION_JSON)
    public Map<String, String> updateControls(@PathParam("id") String id, Map<String, String> changes) {
        scenario(id);
        return controlStore.update(id, changes == null ? Map.of() : changes);
    }

    private ScenarioDefinition scenario(String id) {
        return registry.find(id).orElseThrow(() -> new ScenarioException(404, "Unknown scenario " + id));
    }

    private DeploymentView view(Deployment d) {
        return new DeploymentView(d.state().name(), d.runState().name(), d.message(), d.groupId(),
                d.groupId() == null ? null : links.processGroup(d.groupId()));
    }
}
