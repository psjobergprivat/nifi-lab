package org.mylabs.nifilab.scenario;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

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
        @JsonIgnore byte[] flowDefinition) {

    public ScenarioDefinition {
        learn = learn == null ? List.of() : List.copyOf(learn);
        samples = samples == null ? List.of() : List.copyOf(samples);
        lanes = lanes == null ? List.of() : List.copyOf(lanes);
        steps = steps == null ? List.of() : List.copyOf(steps);
        controls = controls == null ? List.of() : List.copyOf(controls);
        expectedBulletins = expectedBulletins == null ? List.of() : List.copyOf(expectedBulletins);
    }

    ScenarioDefinition withFlowDefinition(byte[] flow) {
        return new ScenarioDefinition(id, order, title, summary, learn, input, samples, lanes, steps, controls, expectedBulletins, flow);
    }

    /**
     * How the user feeds data into the flow: an HTTP POST to the flow's ListenHTTP processor.
     *
     * @param port        ListenHTTP "Listening Port"
     * @param path        ListenHTTP "Base Path"
     * @param contentType Content-Type sent with the input
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Input(String type, int port, String path, String contentType, String placeholder) {
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
     * @param type slider or select
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Control(String id, String type, String label, String description, Integer min, Integer max,
                          Integer step, String unit, List<String> options, String defaultValue) {
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
