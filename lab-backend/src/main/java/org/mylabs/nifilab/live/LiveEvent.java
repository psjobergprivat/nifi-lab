package org.mylabs.nifilab.live;

/**
 * An event pushed to the browser over SSE.
 *
 * @param type one of {@code status, bulletin, item, input, reset, deployment, nifi, heartbeat}
 */
public record LiveEvent(String type, String scenarioId, long timestamp, Object data) {

    public static LiveEvent of(String type, String scenarioId, Object data) {
        return new LiveEvent(type, scenarioId, System.currentTimeMillis(), data);
    }
}
