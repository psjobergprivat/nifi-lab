package org.mylabs.nifilab.nifi;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;
import org.jboss.resteasy.reactive.RestForm;

/** Single-user login; kept separate from {@link NifiApi} so it is not intercepted by the auth filter. */
@RegisterRestClient(configKey = "nifi")
public interface NifiLoginApi {

    @POST
    @Path("/access/token")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_PLAIN)
    String token(@RestForm String username, @RestForm String password);
}
