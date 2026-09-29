package org.mylabs.nifilab;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.mylabs.nifilab.nifi.NifiException;
import org.mylabs.nifilab.nifi.NifiService;

@Path("/api/nifi")
@Produces(MediaType.APPLICATION_JSON)
public class NifiInfoResource {

    @Inject
    NifiService nifi;

    @Inject
    LabConfig config;

    public record NifiInfo(boolean reachable, String version, String uiUrl, String message) {
    }

    @GET
    public NifiInfo info() {
        String uiUrl = config.nifi().uiUrl() + "/nifi/";
        try {
            return new NifiInfo(true, nifi.version(), uiUrl, null);
        } catch (NifiException e) {
            return new NifiInfo(false, null, uiUrl, e.getMessage());
        }
    }
}
