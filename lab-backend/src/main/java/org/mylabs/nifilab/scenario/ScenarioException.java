package org.mylabs.nifilab.scenario;

/** A scenario operation that cannot be performed; {@code status} is the HTTP status returned to the UI. */
public class ScenarioException extends RuntimeException {

    private final int status;

    public ScenarioException(int status, String message) {
        super(message);
        this.status = status;
    }

    public int status() {
        return status;
    }
}
