package org.mylabs.nifilab.nifi;

import com.fasterxml.jackson.databind.JsonNode;
import org.mylabs.nifilab.nifi.NifiModel.Bulletin;
import org.mylabs.nifilab.nifi.NifiModel.ConnectionStatus;
import org.mylabs.nifilab.nifi.NifiModel.FlowEdge;
import org.mylabs.nifilab.nifi.NifiModel.FlowGraph;
import org.mylabs.nifilab.nifi.NifiModel.FlowNode;
import org.mylabs.nifilab.nifi.NifiModel.FlowStatus;
import org.mylabs.nifilab.nifi.NifiModel.GroupRef;
import org.mylabs.nifilab.nifi.NifiModel.ProcessorStatus;

import java.util.ArrayList;
import java.util.List;

/** Maps NiFi REST entities (as JSON) to {@link NifiModel} records. */
public final class NifiMapper {

    private NifiMapper() {
    }

    /** Child process groups of a {@code GET /flow/process-groups/{id}} response. */
    public static List<GroupRef> childGroups(JsonNode processGroupFlow) {
        List<GroupRef> groups = new ArrayList<>();
        for (JsonNode pg : processGroupFlow.path("processGroupFlow").path("flow").path("processGroups")) {
            groups.add(new GroupRef(
                    pg.path("id").asText(),
                    pg.path("component").path("name").asText(),
                    pg.path("component").path("comments").asText(""),
                    pg.path("revision").path("version").asLong()));
        }
        return groups;
    }

    public static FlowGraph graph(JsonNode processGroupFlow) {
        JsonNode pgf = processGroupFlow.path("processGroupFlow");
        String groupId = pgf.path("id").asText();
        JsonNode flow = pgf.path("flow");

        List<FlowNode> nodes = new ArrayList<>();
        for (JsonNode p : flow.path("processors")) {
            nodes.add(node(p, "PROCESSOR", shortType(p.path("component").path("type").asText())));
        }
        for (JsonNode p : flow.path("inputPorts")) {
            nodes.add(node(p, "INPUT_PORT", "Input Port"));
        }
        for (JsonNode p : flow.path("outputPorts")) {
            nodes.add(node(p, "OUTPUT_PORT", "Output Port"));
        }
        for (JsonNode p : flow.path("funnels")) {
            nodes.add(node(p, "FUNNEL", "Funnel"));
        }
        for (JsonNode p : flow.path("processGroups")) {
            nodes.add(node(p, "PROCESS_GROUP", "Process Group"));
        }

        List<FlowEdge> edges = new ArrayList<>();
        for (JsonNode c : flow.path("connections")) {
            // Connections into/out of a child group point at a port inside it; draw them to the group itself.
            String source = groupId.equals(c.path("sourceGroupId").asText(groupId))
                    ? c.path("sourceId").asText() : c.path("sourceGroupId").asText();
            String destination = groupId.equals(c.path("destinationGroupId").asText(groupId))
                    ? c.path("destinationId").asText() : c.path("destinationGroupId").asText();
            List<String> relationships = new ArrayList<>();
            c.path("component").path("selectedRelationships").forEach(r -> relationships.add(r.asText()));
            edges.add(new FlowEdge(c.path("id").asText(), source, destination, relationships));
        }
        return new FlowGraph(groupId, nodes, edges);
    }

    public static FlowStatus status(JsonNode processGroupStatus) {
        JsonNode aggregate = processGroupStatus.path("processGroupStatus").path("aggregateSnapshot");
        List<ProcessorStatus> processors = new ArrayList<>();
        List<ConnectionStatus> connections = new ArrayList<>();
        collectStatus(aggregate, processors, connections);
        return new FlowStatus(
                processGroupStatus.path("processGroupStatus").path("id").asText(),
                processors, connections,
                aggregate.path("flowFilesQueued").asInt(),
                aggregate.path("activeThreadCount").asInt());
    }

    private static void collectStatus(JsonNode snapshot, List<ProcessorStatus> processors,
                                      List<ConnectionStatus> connections) {
        for (JsonNode e : snapshot.path("processorStatusSnapshots")) {
            JsonNode s = e.path("processorStatusSnapshot");
            processors.add(new ProcessorStatus(
                    s.path("id").asText(e.path("id").asText()),
                    s.path("name").asText(),
                    s.path("runStatus").asText(),
                    s.path("flowFilesIn").asInt(),
                    s.path("flowFilesOut").asInt(),
                    s.path("taskCount").asInt(),
                    s.path("activeThreadCount").asInt()));
        }
        for (JsonNode e : snapshot.path("connectionStatusSnapshots")) {
            JsonNode s = e.path("connectionStatusSnapshot");
            connections.add(new ConnectionStatus(
                    s.path("id").asText(e.path("id").asText()),
                    s.path("sourceId").asText(),
                    s.path("destinationId").asText(),
                    s.path("flowFilesQueued").asInt(),
                    s.path("bytesQueued").asLong(),
                    s.path("percentUseCount").asInt(),
                    s.path("percentUseBytes").asInt()));
        }
        for (JsonNode child : snapshot.path("processGroupStatusSnapshots")) {
            collectStatus(child.path("processGroupStatusSnapshot"), processors, connections);
        }
    }

    public static List<Bulletin> bulletins(JsonNode bulletinBoard) {
        List<Bulletin> bulletins = new ArrayList<>();
        for (JsonNode e : bulletinBoard.path("bulletinBoard").path("bulletins")) {
            JsonNode b = e.path("bulletin");
            if (b.isMissingNode()) {
                continue; // not readable for this user
            }
            bulletins.add(new Bulletin(
                    e.path("id").asLong(),
                    e.path("sourceId").asText(),
                    b.path("sourceName").asText(),
                    b.path("level").asText(),
                    b.path("message").asText(),
                    e.path("timestamp").asText(b.path("timestamp").asText())));
        }
        return bulletins;
    }

    private static FlowNode node(JsonNode entity, String kind, String type) {
        return new FlowNode(
                entity.path("id").asText(),
                kind,
                entity.path("component").path("name").asText(),
                type,
                entity.path("position").path("x").asDouble(),
                entity.path("position").path("y").asDouble());
    }

    /** org.apache.nifi.processors.standard.ListenHTTP → ListenHTTP */
    static String shortType(String type) {
        int dot = type.lastIndexOf('.');
        return dot < 0 ? type : type.substring(dot + 1);
    }
}
