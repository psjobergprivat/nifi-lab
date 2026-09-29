package org.mylabs.nifilab.nifi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mylabs.nifilab.nifi.NifiModel.FlowGraph;
import org.mylabs.nifilab.nifi.NifiModel.FlowStatus;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class NifiMapperTest {

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void mapsGraphAndRedirectsEdgesIntoChildGroups() throws Exception {
        JsonNode flow = json.readTree("""
                {"processGroupFlow": {"id": "pg", "flow": {
                  "processors": [
                    {"id": "p1", "position": {"x": 1, "y": 2},
                     "component": {"name": "Receive", "type": "org.apache.nifi.processors.standard.ListenHTTP"}}
                  ],
                  "processGroups": [{"id": "child", "position": {"x": 0, "y": 300}, "component": {"name": "Inner"}}],
                  "funnels": [{"id": "f1", "position": {"x": 5, "y": 5}, "component": {}}],
                  "connections": [
                    {"id": "c1", "sourceId": "p1", "sourceGroupId": "pg", "destinationId": "in-port",
                     "destinationGroupId": "child", "component": {"selectedRelationships": ["success"]}}
                  ]
                }}}""");

        FlowGraph graph = NifiMapper.graph(flow);

        assertEquals("pg", graph.groupId());
        assertEquals(3, graph.nodes().size());
        assertEquals("ListenHTTP", graph.nodes().get(0).type());
        assertEquals(1.0, graph.nodes().get(0).x());
        assertEquals("child", graph.edges().get(0).destinationId());
        assertEquals(List.of("success"), graph.edges().get(0).relationships());
    }

    @Test
    void flattensRecursiveStatus() throws Exception {
        JsonNode status = json.readTree("""
                {"processGroupStatus": {"id": "pg", "aggregateSnapshot": {
                  "flowFilesQueued": 7, "activeThreadCount": 1,
                  "processorStatusSnapshots": [
                    {"id": "p1", "processorStatusSnapshot": {"id": "p1", "name": "A", "runStatus": "Running",
                      "flowFilesIn": 3, "flowFilesOut": 2, "taskCount": 5, "activeThreadCount": 1}}
                  ],
                  "connectionStatusSnapshots": [
                    {"id": "c1", "connectionStatusSnapshot": {"id": "c1", "sourceId": "p1", "destinationId": "p2",
                      "flowFilesQueued": 7, "bytesQueued": 70, "percentUseCount": 1, "percentUseBytes": 0}}
                  ],
                  "processGroupStatusSnapshots": [
                    {"processGroupStatusSnapshot": {"processorStatusSnapshots": [
                      {"id": "p9", "processorStatusSnapshot": {"id": "p9", "name": "Nested", "runStatus": "Stopped"}}
                    ]}}
                  ]
                }}}""");

        FlowStatus mapped = NifiMapper.status(status);

        assertEquals(7, mapped.queuedCount());
        assertEquals(List.of("p1", "p9"), mapped.processors().stream().map(NifiModel.ProcessorStatus::id).toList());
        assertEquals(70, mapped.connections().get(0).queuedBytes());
    }

    @Test
    void skipsUnreadableBulletins() throws Exception {
        JsonNode board = json.readTree("""
                {"bulletinBoard": {"bulletins": [
                  {"id": 4, "sourceId": "p1", "timestamp": "10:00:00 UTC",
                   "bulletin": {"sourceName": "Deliver", "level": "ERROR", "message": "Connection refused"}},
                  {"id": 5, "sourceId": "p2", "canRead": false}
                ]}}""");

        List<NifiModel.Bulletin> bulletins = NifiMapper.bulletins(board);

        assertEquals(1, bulletins.size());
        assertEquals("ERROR", bulletins.get(0).level());
        assertEquals(4, bulletins.get(0).id());
    }

    @Test
    void lineageJoinsLinksThroughFlowFileNodesAndMergesParentRequests() throws Exception {
        // Child FlowFile c: JOIN(3) -> SEND(4). Its lineage links through the FlowFile node "c".
        JsonNode child = json.readTree("""
                {"lineage": {"results": {
                  "nodes": [
                    {"id": "3", "type": "EVENT", "eventType": "JOIN", "flowFileUuid": "c", "millis": 30, "parentUuids": ["p1"]},
                    {"id": "c", "type": "FLOWFILE", "flowFileUuid": "c"},
                    {"id": "4", "type": "EVENT", "eventType": "SEND", "flowFileUuid": "c", "millis": 40, "parentUuids": []}
                  ],
                  "links": [{"sourceId": "3", "targetId": "c", "flowFileUuid": "c"},
                            {"sourceId": "c", "targetId": "4", "flowFileUuid": "c"}]
                }}}""");
        // Parent FlowFile p1 (from a PARENTS request): RECEIVE(1) -> JOIN(3), repeating event 3.
        JsonNode parents = json.readTree("""
                {"lineage": {"results": {
                  "nodes": [
                    {"id": "1", "type": "EVENT", "eventType": "RECEIVE", "flowFileUuid": "p1", "millis": 10, "parentUuids": []},
                    {"id": "3", "type": "EVENT", "eventType": "JOIN", "flowFileUuid": "c", "millis": 30, "parentUuids": ["p1"]}
                  ],
                  "links": [{"sourceId": "1", "targetId": "3", "flowFileUuid": "p1"}]
                }}}""");
        JsonNode details = json.readTree("""
                {"provenanceEvent": {"componentName": "Merge", "attributes": [
                  {"name": "kept", "value": "x", "previousValue": "x"},
                  {"name": "added", "value": "y", "previousValue": null}]}}""");

        assertEquals(List.of("3"), NifiMapper.eventsWithParents(List.of(child)));
        NifiModel.Lineage lineage = NifiMapper.lineage("c", List.of(child, parents), id -> details);

        assertEquals(List.of("1", "3", "4"), lineage.events().stream().map(NifiModel.LineageEvent::id).toList());
        assertEquals(java.util.Set.of("1->3", "3->4"),
                lineage.links().stream().map(l -> l.sourceId() + "->" + l.targetId()).collect(java.util.stream.Collectors.toSet()));
        assertEquals("Merge", lineage.events().get(1).componentName());
        assertEquals(List.of("added"),
                lineage.events().get(1).attributeChanges().stream().map(NifiModel.AttributeChange::name).toList());
    }
}
