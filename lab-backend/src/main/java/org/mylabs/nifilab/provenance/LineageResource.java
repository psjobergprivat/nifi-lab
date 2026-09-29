package org.mylabs.nifilab.provenance;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.mylabs.nifilab.nifi.NifiModel.Lineage;
import org.mylabs.nifilab.nifi.NifiService;
import org.mylabs.nifilab.scenario.DeepLinks;

@Path("/api/lineage")
@Produces(MediaType.APPLICATION_JSON)
public class LineageResource {

    @Inject
    NifiService nifi;

    @Inject
    DeepLinks links;

    public record LineageView(Lineage lineage, String provenanceLink) {
    }

    /** "Where did this come from?" for a FlowFile, identified by its uuid attribute. */
    @GET
    @Path("/{flowFileUuid}")
    public LineageView lineage(@PathParam("flowFileUuid") String flowFileUuid) {
        return new LineageView(nifi.lineage(flowFileUuid), links.provenance());
    }
}
