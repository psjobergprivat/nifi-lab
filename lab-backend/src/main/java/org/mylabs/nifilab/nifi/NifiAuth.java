package org.mylabs.nifilab.nifi;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.client.ClientRequestContext;
import jakarta.ws.rs.client.ClientRequestFilter;
import jakarta.ws.rs.core.HttpHeaders;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.mylabs.nifilab.LabConfig;

/**
 * Holds the NiFi bearer token (obtained lazily with the single-user credentials) and adds it to every
 * {@link NifiApi} request. {@link NifiService} calls {@link #invalidate()} on a 401 and retries once.
 */
@ApplicationScoped
public class NifiAuth implements ClientRequestFilter {

    @Inject
    LabConfig config;

    @Inject
    @RestClient
    NifiLoginApi loginApi;

    private volatile String token;

    @Override
    public void filter(ClientRequestContext requestContext) {
        requestContext.getHeaders().putSingle(HttpHeaders.AUTHORIZATION, "Bearer " + token());
    }

    synchronized String token() {
        if (token == null) {
            token = loginApi.token(config.nifi().username(), config.nifi().password());
        }
        return token;
    }

    void invalidate() {
        token = null;
    }
}
