package org.mylabs.nifilab.scenario;

import org.mylabs.nifilab.nifi.NifiModel.FlowNode;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Where (and whether) a scenario lives in NiFi.
 *
 * @param components NiFi components of the scenario's process group, by name (names are how
 *                   scenario.yaml refers to them, since NiFi assigns new ids on import)
 */
public record Deployment(String scenarioId, State state, String groupId, Map<String, FlowNode> components,
                         RunState runState, String message) {

    public enum State {PENDING, DEPLOYING, DEPLOYED, FAILED}

    public enum RunState {UNKNOWN, STOPPED, RUNNING}

    public Deployment {
        components = components == null ? Map.of() : Map.copyOf(components);
    }

    static Deployment pending(String scenarioId) {
        return new Deployment(scenarioId, State.PENDING, null, null, RunState.UNKNOWN, "Waiting for NiFi");
    }

    Deployment with(State state, String message) {
        return new Deployment(scenarioId, state, groupId, components, runState, message);
    }

    Deployment with(RunState runState) {
        return new Deployment(scenarioId, state, groupId, components, runState, message);
    }

    public boolean deployed() {
        return state == State.DEPLOYED;
    }

    public Optional<FlowNode> component(String name) {
        return Optional.ofNullable(components.get(name));
    }

    List<String> processorIds() {
        return components.values().stream().filter(n -> "PROCESSOR".equals(n.kind())).map(FlowNode::id).toList();
    }
}
