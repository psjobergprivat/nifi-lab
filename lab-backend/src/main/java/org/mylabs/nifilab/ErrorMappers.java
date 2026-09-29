package org.mylabs.nifilab;

import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.resteasy.reactive.server.ServerExceptionMapper;
import org.mylabs.nifilab.nifi.NifiException;
import org.mylabs.nifilab.scenario.ScenarioException;

/** Turns lab exceptions into {@code {"message": ...}} responses the UI can show as-is. */
public class ErrorMappers {

    public record Error(String message) {
    }

    @ServerExceptionMapper
    public Response scenario(ScenarioException e) {
        return json(e.status(), e.getMessage());
    }

    @ServerExceptionMapper
    public Response nifi(NifiException e) {
        // 0 = NiFi unreachable; anything else is NiFi refusing the request.
        return json(e.status() == 0 ? 503 : 502, e.getMessage());
    }

    private static Response json(int status, String message) {
        return Response.status(status).type(MediaType.APPLICATION_JSON).entity(new Error(message)).build();
    }
}
