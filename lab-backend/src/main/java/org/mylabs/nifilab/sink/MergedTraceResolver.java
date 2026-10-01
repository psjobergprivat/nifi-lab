package org.mylabs.nifilab.sink;

import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;
import org.mylabs.nifilab.live.LiveHub;
import org.mylabs.nifilab.nifi.NifiMapper;
import org.mylabs.nifilab.nifi.NifiService;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * A FlowFile merged from several inputs (MergeContent) keeps only the attributes they all share, so it arrives
 * without a lab.trace.id. Its provenance lineage still knows every input it came from: this looks the lineage up
 * in the background and publishes a {@code traces} event, so the lab can mark those inputs as delivered.
 */
@ApplicationScoped
public class MergedTraceResolver {

    private static final Logger LOG = Logger.getLogger(MergedTraceResolver.class);

    /** NiFi indexes provenance events asynchronously, so a lineage queried right away can still be incomplete. */
    private static final long[] RETRY_DELAYS_MS = {1000, 2000, 3000, 4000};

    public record MergedTraces(String flowFileUuid, List<String> traceIds) {
    }

    @Inject
    NifiService nifi;

    @Inject
    LiveHub hub;

    private final ScheduledExecutorService executor = Executors.newScheduledThreadPool(2, r -> {
        Thread thread = new Thread(r, "merged-trace-resolver");
        thread.setDaemon(true);
        return thread;
    });

    public void resolveLater(String scenarioId, String flowFileUuid) {
        schedule(scenarioId, flowFileUuid, 0);
    }

    private void schedule(String scenarioId, String flowFileUuid, int attempt) {
        executor.schedule(() -> resolve(scenarioId, flowFileUuid, attempt), RETRY_DELAYS_MS[attempt], TimeUnit.MILLISECONDS);
    }

    private void resolve(String scenarioId, String flowFileUuid, int attempt) {
        List<String> traceIds = List.of();
        try {
            traceIds = NifiMapper.receivedTraceIds(nifi.lineage(flowFileUuid));
        } catch (RuntimeException e) {
            LOG.debugf("Lineage of %s not available yet: %s", flowFileUuid, e.getMessage());
        }
        if (!traceIds.isEmpty()) {
            hub.publish("traces", scenarioId, new MergedTraces(flowFileUuid, traceIds));
        } else if (attempt + 1 < RETRY_DELAYS_MS.length) {
            schedule(scenarioId, flowFileUuid, attempt + 1);
        } else {
            LOG.infof("Could not find the inputs of merged FlowFile %s in its lineage", flowFileUuid);
        }
    }

    @PreDestroy
    void stop() {
        executor.shutdownNow();
    }
}
