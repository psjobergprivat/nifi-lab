package org.mylabs.nifilab;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

@ConfigMapping(prefix = "lab")
public interface LabConfig {

    Nifi nifi();

    /**
     * Base URL NiFi uses to call back into this backend (the sink). From inside Docker this is
     * {@code http://lab-backend:8080}; when the backend runs on the host in dev mode it is
     * {@code http://host.docker.internal:8080}.
     */
    String sinkBaseUrl();

    /** Deploy all scenario flows into NiFi automatically (retried until NiFi is reachable). */
    @WithDefault("true")
    boolean deployOnStartup();

    /** Re-import a scenario flow even if the deployed one matches flow.json. */
    @WithDefault("false")
    boolean forceRedeploy();

    Live live();

    Database database();

    /** The lab's PostgreSQL database (scenario 10), read directly by the backend to show what NiFi wrote. */
    interface Database {
        String url();

        String user();

        String password();
    }

    interface Live {
        /** How often NiFi status is polled for scenarios someone is watching (used by {@code StatusPoller}). */
        @WithDefault("1s")
        String pollInterval();
    }

    interface Nifi {
        /** NiFi base URL as seen from the backend, e.g. https://nifi:8443 */
        String url();

        /** NiFi base URL as seen from the user's browser, used for deep links. */
        String uiUrl();

        String username();

        String password();

        /** Host where the scenarios' ListenHTTP processors can be reached from the backend. */
        String listenHost();
    }
}
