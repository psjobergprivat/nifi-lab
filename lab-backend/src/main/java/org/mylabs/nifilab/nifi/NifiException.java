package org.mylabs.nifilab.nifi;

import jakarta.ws.rs.WebApplicationException;

/** A failed NiFi call. {@code status} is the HTTP status, or 0 if NiFi could not be reached. */
public class NifiException extends RuntimeException {

    private final int status;

    public NifiException(int status, String message, Throwable cause) {
        super(message, cause);
        this.status = status;
    }

    static NifiException from(WebApplicationException e) {
        int status = e.getResponse().getStatus();
        String body;
        try {
            body = e.getResponse().readEntity(String.class);
        } catch (RuntimeException ignored) {
            body = null;
        }
        String message = "NiFi responded " + status + (body == null || body.isBlank() ? "" : ": " + body.strip());
        return new NifiException(status, message, e);
    }

    public int status() {
        return status;
    }
}
