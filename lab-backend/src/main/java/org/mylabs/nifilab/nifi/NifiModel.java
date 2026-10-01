package org.mylabs.nifilab.nifi;

import java.util.List;

/** The lab's own, deliberately small view of NiFi entities. */
public final class NifiModel {

    private NifiModel() {
    }

    /** A process group found under the root canvas. */
    public record GroupRef(String id, String name, String comments, long revision) {
    }

    /** Topology of a process group: what to draw. */
    public record FlowGraph(String groupId, List<FlowNode> nodes, List<FlowEdge> edges) {
    }

    /** @param kind PROCESSOR, INPUT_PORT, OUTPUT_PORT, FUNNEL or PROCESS_GROUP */
    public record FlowNode(String id, String kind, String name, String type, double x, double y) {
    }

    public record FlowEdge(String id, String sourceId, String destinationId, List<String> relationships) {
    }

    /** Live numbers for a process group. Counts are NiFi's rolling 5-minute window. */
    public record FlowStatus(String groupId, List<ProcessorStatus> processors, List<ConnectionStatus> connections,
                             int queuedCount, int activeThreads) {
    }

    public record ProcessorStatus(String id, String name, String runStatus, int flowFilesIn, int flowFilesOut,
                                  int taskCount, int activeThreads) {
    }

    public record ConnectionStatus(String id, String sourceId, String destinationId, int queuedCount,
                                   long queuedBytes, int percentUseCount, int percentUseBytes) {
    }

    /** {@code timestamp} is NiFi's text ("18:36:13 UTC"); {@code time} the same moment in epoch millis (0 if unknown). */
    public record Bulletin(long id, String sourceId, String sourceName, String level, String message,
                           String timestamp, long time) {
    }

    /** How one FlowFile came to be: provenance events and the order they happened in. */
    public record Lineage(String flowFileUuid, List<LineageEvent> events, List<LineageLink> links) {
    }

    public record LineageEvent(String id, String eventType, String componentId, String componentName,
                               String componentType, long millis, String flowFileUuid, String details,
                               List<AttributeChange> attributeChanges) {
    }

    /** @param previousValue null if the attribute is new */
    public record AttributeChange(String name, String value, String previousValue) {
    }

    public record LineageLink(String sourceId, String targetId, String flowFileUuid) {
    }

    /** One key/value a processor keeps in NiFi's state manager (e.g. ListFile's last listed timestamp). */
    public record StateEntry(String key, String value, String scope) {
    }

    /** A parameter as NiFi reports it; sensitive values come back masked. */
    public record ParameterValue(String name, String value, boolean sensitive) {
    }

    public record ParameterContextRef(String id, String name) {
    }
}
