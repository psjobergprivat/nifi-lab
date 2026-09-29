package org.mylabs.nifilab.scenario;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;
import org.mylabs.nifilab.nifi.NifiModel.ParameterContextRef;
import org.mylabs.nifilab.nifi.NifiModel.ParameterValue;
import org.mylabs.nifilab.nifi.NifiService;
import org.mylabs.nifilab.scenario.ScenarioDefinition.Control;
import org.mylabs.nifilab.scenario.ScenarioDefinition.ParameterContextDef;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * NiFi parameter contexts declared in scenario.yaml ({@code parameterContexts}) and the controls that act on them
 * ({@code target: parameter} / {@code target: parameter-context}).
 */
@ApplicationScoped
public class ParameterService {

    private static final Logger LOG = Logger.getLogger(ParameterService.class);

    @Inject
    NifiService nifi;

    @Inject
    ScenarioRegistry registry;

    @Inject
    ScenarioService scenarios;

    /**
     * Makes sure every declared context exists in NiFi with its parameters, and that the group is bound to one.
     * Sensitive values cannot travel in flow.json, so they are (re)set here after every deployment.
     */
    public void ensure(ScenarioDefinition scenario, String groupId) {
        if (scenario.parameterContexts().isEmpty()) {
            return;
        }
        for (ParameterContextDef def : scenario.parameterContexts()) {
            Optional<ParameterContextRef> existing = nifi.findParameterContext(def.name());
            if (existing.isEmpty()) {
                nifi.createParameterContext(def.name(), def.description(), values(def));
                continue;
            }
            Set<String> present = nifi.parameters(existing.get().id()).stream()
                    .filter(p -> p.value() != null)
                    .map(ParameterValue::name)
                    .collect(Collectors.toSet());
            List<ParameterValue> missing = values(def).stream()
                    .filter(p -> p.sensitive() || !present.contains(p.name()))
                    .toList();
            if (!missing.isEmpty()) {
                nifi.updateParameters(existing.get().id(), missing);
            }
        }
        if (nifi.boundParameterContextId(groupId).isEmpty()) {
            bind(scenario, groupId, scenario.parameterContexts().get(0));
        }
    }

    /** The values of NiFi-backed controls as NiFi has them now (they can also be changed in the NiFi UI). */
    public Map<String, String> currentValues(ScenarioDefinition scenario, String groupId) {
        Map<String, String> values = new HashMap<>();
        if (scenario.controls().stream().noneMatch(Control::targetsNifi)) {
            return values;
        }
        Optional<String> boundId = nifi.boundParameterContextId(groupId);
        if (boundId.isEmpty()) {
            return values;
        }
        Map<String, String> parameters = new HashMap<>();
        nifi.parameters(boundId.get()).forEach(p -> parameters.put(p.name(), p.value()));
        Optional<ParameterContextDef> bound = definitionFor(scenario, boundId.get());
        for (Control control : scenario.controls()) {
            if ("parameter-context".equals(control.target())) {
                bound.ifPresent(def -> values.put(control.id(), def.id()));
            } else if ("parameter".equals(control.target()) && parameters.get(control.parameter()) != null) {
                values.put(control.id(), parameters.get(control.parameter()));
            }
        }
        return values;
    }

    /** Applies a NiFi-backed control: switch the group's parameter context, or change one parameter value. */
    public void apply(ScenarioDefinition scenario, Control control, String value) {
        Deployment deployment = scenarios.deployment(scenario.id());
        if (!deployment.deployed()) {
            throw new ScenarioException(409, "Scenario " + scenario.id() + " is not deployed to NiFi yet");
        }
        String groupId = deployment.groupId();
        if ("parameter-context".equals(control.target())) {
            ParameterContextDef def = scenario.parameterContexts().stream()
                    .filter(d -> d.id().equals(value)).findFirst()
                    .orElseThrow(() -> new ScenarioException(400, "Unknown parameter context " + value));
            bind(scenario, groupId, def);
        } else if ("parameter".equals(control.target())) {
            String contextId = nifi.boundParameterContextId(groupId)
                    .orElseThrow(() -> new ScenarioException(409, "The process group has no parameter context"));
            nifi.updateParameters(contextId, List.of(new ParameterValue(control.parameter(), value, false)));
        }
    }

    /** On reset: every context back to its scenario.yaml values, and the group back on the first context. */
    void onReset(@Observes ScenarioReset reset) {
        registry.find(reset.scenarioId()).ifPresent(scenario -> {
            Deployment deployment = scenarios.deployment(scenario.id());
            if (scenario.parameterContexts().isEmpty() || !deployment.deployed()) {
                return;
            }
            for (ParameterContextDef def : scenario.parameterContexts()) {
                nifi.findParameterContext(def.name()).ifPresent(ref -> nifi.updateParameters(ref.id(), values(def)));
            }
            bind(scenario, deployment.groupId(), scenario.parameterContexts().get(0));
            LOG.debugf("Parameters of scenario %s reset", scenario.id());
        });
    }

    /**
     * Binds the group to a context. Components that use parameters must be stopped for that, so a running
     * group is stopped and started again.
     */
    private void bind(ScenarioDefinition scenario, String groupId, ParameterContextDef def) {
        ParameterContextRef ref = nifi.findParameterContext(def.name())
                .orElseGet(() -> nifi.createParameterContext(def.name(), def.description(), values(def)));
        if (nifi.boundParameterContextId(groupId).filter(ref.id()::equals).isPresent()) {
            return;
        }
        boolean running = scenarios.deployment(scenario.id()).runState() == Deployment.RunState.RUNNING;
        if (running) {
            nifi.stop(groupId);
        }
        nifi.bindParameterContext(groupId, ref.id());
        if (running) {
            nifi.start(groupId);
        }
    }

    private Optional<ParameterContextDef> definitionFor(ScenarioDefinition scenario, String contextId) {
        return scenario.parameterContexts().stream()
                .filter(def -> nifi.findParameterContext(def.name()).map(ParameterContextRef::id)
                        .filter(contextId::equals).isPresent())
                .findFirst();
    }

    private static List<ParameterValue> values(ParameterContextDef def) {
        return def.parameters().stream()
                .map(p -> new ParameterValue(p.name(), p.value(), p.sensitive()))
                .toList();
    }
}
