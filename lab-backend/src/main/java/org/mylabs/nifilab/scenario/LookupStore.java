package org.mylabs.nifilab.scenario;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.mylabs.nifilab.live.LiveHub;
import org.mylabs.nifilab.scenario.ScenarioDefinition.LookupTable;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Current rows of each scenario's editable lookup table (scenario.yaml {@code lookup}). The mock service answers
 * NiFi's lookups from here, so edits in the UI change the next enrichment immediately.
 */
@ApplicationScoped
public class LookupStore {

    @Inject
    ScenarioRegistry registry;

    @Inject
    LiveHub hub;

    private final Map<String, List<Map<String, String>>> rows = new ConcurrentHashMap<>();

    public Optional<LookupTable> table(String scenarioId) {
        return registry.find(scenarioId)
                .map(ScenarioDefinition::lookup)
                .map(def -> new LookupTable(def.key(), def.columns(), rows.getOrDefault(scenarioId, def.rows())));
    }

    public Optional<Map<String, String>> find(String scenarioId, String key) {
        return table(scenarioId).flatMap(t -> t.rows().stream()
                .filter(row -> key.equals(row.get(t.key())))
                .findFirst());
    }

    public LookupTable replace(String scenarioId, List<Map<String, String>> newRows) {
        LookupTable current = table(scenarioId)
                .orElseThrow(() -> new ScenarioException(404, "Scenario " + scenarioId + " has no lookup table"));
        for (Map<String, String> row : newRows) {
            String key = row.get(current.key());
            if (key == null || key.isBlank()) {
                throw new ScenarioException(400, "Every row needs a value for " + current.key());
            }
        }
        rows.put(scenarioId, newRows.stream().map(Map::copyOf).toList());
        LookupTable updated = table(scenarioId).orElseThrow();
        hub.publish("lookup", scenarioId, updated);
        return updated;
    }

    void onReset(@Observes ScenarioReset reset) {
        if (rows.remove(reset.scenarioId()) != null) {
            table(reset.scenarioId()).ifPresent(t -> hub.publish("lookup", reset.scenarioId(), t));
        }
    }
}
