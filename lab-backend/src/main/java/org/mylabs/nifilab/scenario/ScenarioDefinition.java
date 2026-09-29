package org.mylabs.nifilab.scenario;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.Map;

/**
 * A scenario as described by {@code scenarios/<id>/scenario.yaml}. The flow definition itself
 * ({@code flow.json}) is loaded separately by {@link ScenarioRegistry}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ScenarioDefinition(
        String id,
        int order,
        String title,
        String summary,
        List<String> learn,
        Input input,
        List<Sample> samples,
        List<Lane> lanes,
        List<Step> steps,
        List<Control> controls,
        List<ExpectedBulletin> expectedBulletins,
        LookupTable lookup,
        DatabaseView database,
        List<String> stateOf,
        List<ParameterContextDef> parameterContexts,
        @JsonIgnore byte[] flowDefinition) {

    public ScenarioDefinition {
        learn = learn == null ? List.of() : List.copyOf(learn);
        samples = samples == null ? List.of() : List.copyOf(samples);
        lanes = lanes == null ? List.of() : List.copyOf(lanes);
        steps = steps == null ? List.of() : List.copyOf(steps);
        controls = controls == null ? List.of() : List.copyOf(controls);
        expectedBulletins = expectedBulletins == null ? List.of() : List.copyOf(expectedBulletins);
        stateOf = stateOf == null ? List.of() : List.copyOf(stateOf);
        parameterContexts = parameterContexts == null ? List.of() : List.copyOf(parameterContexts);
    }

    ScenarioDefinition withFlowDefinition(byte[] flow) {
        return new ScenarioDefinition(id, order, title, summary, learn, input, samples, lanes, steps, controls,
                expectedBulletins, lookup, database, stateOf, parameterContexts, flow);
    }

    /**
     * How the user feeds data into the flow: an HTTP POST to the flow's ListenHTTP processor.
     *
     * @param port        ListenHTTP "Listening Port"
     * @param path        ListenHTTP "Base Path"
     * @param contentType Content-Type sent with the input
     * @param targets     optional alternatives the user can choose between when sending; the chosen id reaches
     *                    the flow as the {@code lab.target} attribute
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Input(String type, int port, String path, String contentType, String placeholder,
                        List<Target> targets) {

        public Input {
            targets = targets == null ? List.of() : List.copyOf(targets);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Target(String id, String label, String description) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Sample(String name, String content) {
    }

    /** An output bucket; flows post results to {@code /api/sink/<scenario>/<lane id>}. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Lane(String id, String title, String description) {
    }

    /**
     * A knob in the UI. Well-known ids are read by the lab: {@code service.mode}, {@code service.failure-rate},
     * {@code service.latency-ms} (the mock service) and {@code producer.rate} (the producer).
     *
     * @param type      slider or select
     * @param target    where the value goes: {@code lab} (default, read by the lab itself), {@code parameter}
     *                  (the NiFi parameter named {@code parameter} in the group's current parameter context) or
     *                  {@code parameter-context} (switches the group to the context with that id)
     * @param parameter NiFi parameter name, for target {@code parameter}
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Control(String id, String type, String label, String description, Integer min, Integer max,
                          Integer step, String unit, List<String> options, String defaultValue, String target,
                          String parameter) {

        public boolean targetsNifi() {
            return "parameter".equals(target) || "parameter-context".equals(target);
        }
    }

    /**
     * An editable lookup table served by the mock service at {@code /mock/<scenario>/lookup/<key>} - e.g. for
     * NiFi's RestLookupService.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record LookupTable(String key, List<String> columns, List<Map<String, String>> rows) {

        public LookupTable {
            columns = columns == null ? List.of() : List.copyOf(columns);
            rows = rows == null ? List.of() : rows.stream().map(Map::copyOf).toList();
        }
    }

    /** A table in the lab database shown next to the flow, and the SQL that empties it on reset. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DatabaseView(String title, String query, String reset) {
    }

    /**
     * A NiFi parameter context the scenario brings along.
     *
     * @param id   short id used by controls (e.g. dev, prod)
     * @param name the context's name in NiFi
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ParameterContextDef(String id, String name, String description, List<Parameter> parameters) {

        public ParameterContextDef {
            parameters = parameters == null ? List.of() : List.copyOf(parameters);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Parameter(String name, String value, boolean sensitive, String description) {
    }

    /**
     * A NiFi bulletin the scenario causes on purpose (e.g. with a deliberately broken sample). The UI marks
     * bulletins from {@code component} whose message contains {@code contains} as expected and shows the
     * explanation instead of presenting them as errors.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ExpectedBulletin(String component, String contains, String explanation) {
    }

    /** A guided-mode step. {@code component} is the name of a NiFi component in the flow, used for deep links. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Step(String title, String text, String component) {
    }
}
