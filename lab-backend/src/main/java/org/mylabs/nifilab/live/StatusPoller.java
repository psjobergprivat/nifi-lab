package org.mylabs.nifilab.live;

import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.mylabs.nifilab.nifi.NifiException;
import org.mylabs.nifilab.nifi.NifiModel.Bulletin;
import org.mylabs.nifilab.nifi.NifiModel.FlowStatus;
import org.mylabs.nifilab.nifi.NifiService;
import org.mylabs.nifilab.scenario.Deployment;
import org.mylabs.nifilab.scenario.ScenarioService;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Polls NiFi for the scenarios someone is watching and pushes status and new bulletins to them. */
@ApplicationScoped
public class StatusPoller {

    @Inject
    LiveHub hub;

    @Inject
    NifiService nifi;

    @Inject
    ScenarioService scenarios;

    private final Map<String, Long> lastBulletinIds = new ConcurrentHashMap<>();

    @Scheduled(every = "${lab.live.poll-interval:1s}", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void poll() {
        for (String scenarioId : hub.watchedScenarios()) {
            Deployment deployment = scenarios.deployment(scenarioId);
            if (!deployment.deployed()) {
                continue;
            }
            try {
                FlowStatus status = nifi.status(deployment.groupId());
                scenarios.observed(scenarioId, status);
                hub.publish("status", scenarioId, status);

                // NiFi keeps bulletins for 5 minutes; only fetch the ones we have not pushed yet.
                for (Bulletin bulletin : nifi.bulletins(deployment.groupId(), lastBulletinIds.get(scenarioId))) {
                    lastBulletinIds.merge(scenarioId, bulletin.id(), Math::max);
                    hub.publish("bulletin", scenarioId, bulletin);
                }
            } catch (NifiException e) {
                hub.publish("nifi", scenarioId, Map.of("reachable", false, "message", e.getMessage()));
            }
        }
    }
}
