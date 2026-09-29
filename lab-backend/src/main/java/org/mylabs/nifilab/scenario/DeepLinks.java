package org.mylabs.nifilab.scenario;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.mylabs.nifilab.LabConfig;
import org.mylabs.nifilab.nifi.NifiModel.FlowNode;

/** Builds links into the NiFi 2.x UI, e.g. {@code https://localhost:8443/nifi/#/process-groups/<pg>/Processor/<id>}. */
@ApplicationScoped
public class DeepLinks {

    @Inject
    LabConfig config;

    public String processGroup(String groupId) {
        return base() + "/process-groups/" + groupId;
    }

    public String component(String groupId, FlowNode node) {
        return processGroup(groupId) + "/" + uiType(node.kind()) + "/" + node.id();
    }

    public String connection(String groupId, String connectionId) {
        return processGroup(groupId) + "/Connection/" + connectionId;
    }

    public String provenance() {
        return base() + "/provenance";
    }

    public String bulletins() {
        return base() + "/bulletins";
    }

    private String base() {
        return stripTrailingSlash(config.nifi().uiUrl()) + "/nifi/#";
    }

    static String uiType(String kind) {
        return switch (kind) {
            case "PROCESSOR" -> "Processor";
            case "INPUT_PORT" -> "InputPort";
            case "OUTPUT_PORT" -> "OutputPort";
            case "FUNNEL" -> "Funnel";
            case "PROCESS_GROUP" -> "ProcessGroup";
            default -> throw new IllegalArgumentException("Unknown component kind " + kind);
        };
    }

    private static String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
