package org.mylabs.nifilab.scenario;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.mylabs.nifilab.live.LiveHub;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Current values of each scenario's controls (declared in scenario.yaml), starting from their defaults. */
@ApplicationScoped
public class ControlStore {

    @Inject
    ScenarioRegistry registry;

    @Inject
    LiveHub hub;

    private final Map<String, Map<String, String>> values = new ConcurrentHashMap<>();

    public Map<String, String> values(String scenarioId) {
        return Map.copyOf(values.computeIfAbsent(scenarioId, this::defaults));
    }

    public String value(String scenarioId, String controlId, String fallback) {
        return values(scenarioId).getOrDefault(controlId, fallback);
    }

    public int intValue(String scenarioId, String controlId, int fallback) {
        try {
            return Integer.parseInt(value(scenarioId, controlId, String.valueOf(fallback)));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** Rejects unknown ids and out-of-range values. */
    public void validate(String scenarioId, Map<String, String> changes) {
        ScenarioDefinition scenario = registry.find(scenarioId)
                .orElseThrow(() -> new ScenarioException(404, "Unknown scenario " + scenarioId));
        Map<String, ScenarioDefinition.Control> controls = new LinkedHashMap<>();
        scenario.controls().forEach(c -> controls.put(c.id(), c));
        changes.forEach((id, value) -> validate(controls.get(id), id, value));
    }

    /** Updates the given controls (validated first). */
    public Map<String, String> update(String scenarioId, Map<String, String> changes) {
        validate(scenarioId, changes);
        values.computeIfAbsent(scenarioId, this::defaults).putAll(changes);
        Map<String, String> result = values(scenarioId);
        hub.publish("controls", scenarioId, result);
        return result;
    }

    public void reset(String scenarioId) {
        values.put(scenarioId, defaults(scenarioId));
        hub.publish("controls", scenarioId, values(scenarioId));
    }

    private Map<String, String> defaults(String scenarioId) {
        Map<String, String> defaults = new ConcurrentHashMap<>();
        registry.find(scenarioId).ifPresent(s -> s.controls().stream()
                .filter(c -> c.defaultValue() != null)
                .forEach(c -> defaults.put(c.id(), c.defaultValue())));
        return defaults;
    }

    private static void validate(ScenarioDefinition.Control control, String id, String value) {
        if (control == null) {
            throw new ScenarioException(400, "Unknown control " + id);
        }
        if ("select".equals(control.type())) {
            if (control.options() == null || !control.options().contains(value)) {
                throw new ScenarioException(400, id + " must be one of " + control.options());
            }
            return;
        }
        try {
            int number = Integer.parseInt(value);
            if ((control.min() != null && number < control.min()) || (control.max() != null && number > control.max())) {
                throw new ScenarioException(400, id + " must be between " + control.min() + " and " + control.max());
            }
        } catch (NumberFormatException e) {
            throw new ScenarioException(400, id + " must be a number");
        }
    }
}
