package org.mylabs.nifilab.scenario;

/** CDI event fired after a scenario was reset, so helpers (mock service, producer) can forget their state. */
public record ScenarioReset(String scenarioId) {
}
