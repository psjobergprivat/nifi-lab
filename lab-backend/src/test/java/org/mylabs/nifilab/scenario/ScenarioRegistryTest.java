package org.mylabs.nifilab.scenario;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Static checks that every scenario.yaml and flow.json fit together, so mistakes show up without NiFi. */
class ScenarioRegistryTest {

    private static final String LISTEN_HTTP = "org.apache.nifi.processors.standard.ListenHTTP";
    private final ScenarioRegistry registry = new ScenarioRegistry();

    @Test
    void loadsScenariosInOrder() {
        List<ScenarioDefinition> all = registry.all();
        assertFalse(all.isEmpty());
        assertEquals("hello-flowfile", all.get(0).id());
        assertTrue(registry.find("hello-flowfile").isPresent());
    }

    @TestFactory
    Stream<DynamicTest> scenarioMatchesItsFlow() {
        return registry.all().stream().map(s -> DynamicTest.dynamicTest(s.id(), () -> {
            JsonNode contents = new ObjectMapper().readTree(s.flowDefinition()).path("flowContents");
            assertNotNull(s.title(), "title");
            assertNotNull(s.input(), "input");

            Set<String> names = componentNames(contents);
            for (ScenarioDefinition.Step step : s.steps()) {
                if (step.component() != null) {
                    assertTrue(names.contains(step.component()),
                            "step '" + step.title() + "' refers to unknown component '" + step.component() + "'");
                }
            }

            for (ScenarioDefinition.ExpectedBulletin expected : s.expectedBulletins()) {
                assertTrue(names.contains(expected.component()),
                        "expected bulletin refers to unknown component '" + expected.component() + "'");
                assertFalse(expected.contains() == null || expected.contains().isBlank(), "expected bulletin needs 'contains'");
            }

            for (String component : s.stateOf()) {
                assertTrue(names.contains(component), "stateOf refers to unknown component '" + component + "'");
            }
            Set<String> parameterNames = new HashSet<>();
            s.parameterContexts().forEach(c -> c.parameters().forEach(p -> parameterNames.add(p.name())));
            for (ScenarioDefinition.Control control : s.controls()) {
                if ("parameter".equals(control.target())) {
                    assertTrue(parameterNames.contains(control.parameter()),
                            "control " + control.id() + " refers to undeclared parameter " + control.parameter());
                }
                if ("parameter-context".equals(control.target())) {
                    Set<String> ids = new HashSet<>();
                    s.parameterContexts().forEach(c -> ids.add(c.id()));
                    assertEquals(ids, new HashSet<>(control.options()), "control " + control.id() + " options must be the context ids");
                }
            }

            // Lanes set with a literal value (e.g. "Lane = paid") must be declared in scenario.yaml.
            Set<String> lanes = new HashSet<>();
            s.lanes().forEach(l -> lanes.add(l.id()));
            for (JsonNode p : contents.path("processors")) {
                String lane = p.path("properties").path("lab.lane").asText("");
                if (!lane.isEmpty() && !lane.contains("${")) {
                    assertTrue(lanes.contains(lane), "'" + p.path("name").asText() + "' sets undeclared lane " + lane);
                }
            }

            JsonNode listener = listenHttp(contents);
            assertEquals(String.valueOf(s.input().port()), listener.path("properties").path("Listening Port").asText(),
                    "input.port must match the ListenHTTP port");
            assertEquals(s.input().path(), listener.path("properties").path("Base Path").asText(),
                    "input.path must match the ListenHTTP base path");
        }));
    }

    @Test
    void listenPortsAreUnique() {
        Set<Integer> ports = new HashSet<>();
        registry.all().forEach(s -> assertTrue(ports.add(s.input().port()), "port " + s.input().port() + " reused"));
    }

    private static Set<String> componentNames(JsonNode contents) {
        Set<String> names = new HashSet<>();
        for (String kind : List.of("processors", "inputPorts", "outputPorts", "funnels", "processGroups")) {
            contents.path(kind).forEach(c -> names.add(c.path("name").asText()));
        }
        return names;
    }

    private static JsonNode listenHttp(JsonNode contents) throws IOException {
        for (JsonNode p : contents.path("processors")) {
            if (LISTEN_HTTP.equals(p.path("type").asText())) {
                return p;
            }
        }
        throw new IOException("flow has no ListenHTTP processor");
    }
}
