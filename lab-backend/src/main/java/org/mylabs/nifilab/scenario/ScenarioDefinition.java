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
        @JsonIgnore byte[] flowDefinition) {

    public ScenarioDefinition {
        learn = learn == null ? List.of() : List.copyOf(learn);
        samples = samples == null ? List.of() : List.copyOf(samples);
        lanes = lanes == null ? List.of() : List.copyOf(lanes);
        steps = steps == null ? List.of() : List.copyOf(steps);
    }

    ScenarioDefinition withFlowDefinition(byte[] flow) {
        return new ScenarioDefinition(id, order, title, summary, learn, input, samples, lanes, steps, flow);
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

    /** A guided-mode step. {@code component} is the name of a NiFi component in the flow, used for deep links. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Step(String title, String text, String component) {
    }
}
