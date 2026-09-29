package org.mylabs.nifilab.scenario;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import jakarta.enterprise.context.ApplicationScoped;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Loads the scenarios listed in {@code scenarios/index.yaml} from the classpath. Each scenario lives in
 * {@code scenarios/<id>/} with a {@code scenario.yaml} and a NiFi flow definition {@code flow.json}.
 */
@ApplicationScoped
public class ScenarioRegistry {

    static final String BASE = "scenarios/";

    private final Map<String, ScenarioDefinition> scenarios;

    public ScenarioRegistry() {
        this(load(ScenarioRegistry.class.getClassLoader()));
    }

    ScenarioRegistry(List<ScenarioDefinition> scenarios) {
        Map<String, ScenarioDefinition> byId = new LinkedHashMap<>();
        scenarios.stream()
                .sorted(Comparator.comparingInt(ScenarioDefinition::order))
                .forEach(s -> byId.put(s.id(), s));
        this.scenarios = byId;
    }

    public List<ScenarioDefinition> all() {
        return List.copyOf(scenarios.values());
    }

    public Optional<ScenarioDefinition> find(String id) {
        return Optional.ofNullable(scenarios.get(id));
    }

    static List<ScenarioDefinition> load(ClassLoader classLoader) {
        ObjectMapper yaml = new YAMLMapper();
        ObjectMapper json = new JsonMapper();
        JsonNode index = readTree(yaml, classLoader, BASE + "index.yaml");

        List<ScenarioDefinition> result = new ArrayList<>();
        for (JsonNode idNode : index.path("scenarios")) {
            String id = idNode.asText();
            String dir = BASE + id + "/";
            ScenarioDefinition definition;
            try {
                definition = yaml.treeToValue(readTree(yaml, classLoader, dir + "scenario.yaml"), ScenarioDefinition.class);
            } catch (IOException e) {
                throw new UncheckedIOException("Invalid " + dir + "scenario.yaml", e);
            }
            if (!id.equals(definition.id())) {
                throw new IllegalStateException(dir + "scenario.yaml has id '" + definition.id() + "', expected '" + id + "'");
            }
            byte[] flow = readBytes(classLoader, dir + "flow.json");
            readTree(json, classLoader, dir + "flow.json"); // fail fast on malformed JSON
            result.add(definition.withFlowDefinition(flow));
        }
        return result;
    }

    private static JsonNode readTree(ObjectMapper mapper, ClassLoader classLoader, String resource) {
        try {
            return mapper.readTree(readBytes(classLoader, resource));
        } catch (IOException e) {
            throw new UncheckedIOException("Could not parse " + resource, e);
        }
    }

    private static byte[] readBytes(ClassLoader classLoader, String resource) {
        try (InputStream in = classLoader.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("Missing classpath resource " + resource);
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + resource, e);
        }
    }
}
