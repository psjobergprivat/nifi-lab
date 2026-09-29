package org.mylabs.nifilab.nifi;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.rest.client.annotation.RegisterProvider;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;
import org.jboss.resteasy.reactive.PartFilename;
import org.jboss.resteasy.reactive.PartType;
import org.jboss.resteasy.reactive.RestForm;

/**
 * The subset of the NiFi 2.x REST API the lab uses. Responses are kept as {@link JsonNode} and mapped into
 * the lab's own small records by {@link NifiService}, since NiFi's entities are large.
 */
@RegisterRestClient(configKey = "nifi")
@RegisterProvider(NifiAuth.class)
@Produces(MediaType.APPLICATION_JSON)
public interface NifiApi {

    @GET
    @Path("/flow/about")
    JsonNode about();

    @GET
    @Path("/flow/process-groups/{id}")
    JsonNode processGroupFlow(@PathParam("id") String id);

    @GET
    @Path("/flow/process-groups/{id}/status")
    JsonNode processGroupStatus(@PathParam("id") String id, @QueryParam("recursive") boolean recursive);

    /** Body: {"id": ..., "state": "RUNNING" | "STOPPED"} */
    @PUT
    @Path("/flow/process-groups/{id}")
    @Consumes(MediaType.APPLICATION_JSON)
    JsonNode scheduleComponents(@PathParam("id") String id, JsonNode body);

    /** Body: {"id": ..., "state": "ENABLED" | "DISABLED"} */
    @PUT
    @Path("/flow/process-groups/{id}/controller-services")
    @Consumes(MediaType.APPLICATION_JSON)
    JsonNode activateControllerServices(@PathParam("id") String id, JsonNode body);

    @GET
    @Path("/flow/bulletin-board")
    JsonNode bulletinBoard(@QueryParam("groupId") String groupId, @QueryParam("after") Long after,
                           @QueryParam("limit") Integer limit);

    @GET
    @Path("/process-groups/{id}")
    JsonNode processGroup(@PathParam("id") String id);

    @PUT
    @Path("/process-groups/{id}")
    @Consumes(MediaType.APPLICATION_JSON)
    JsonNode updateProcessGroup(@PathParam("id") String id, JsonNode body);

    @DELETE
    @Path("/process-groups/{id}")
    JsonNode deleteProcessGroup(@PathParam("id") String id, @QueryParam("version") long version,
                                @QueryParam("clientId") String clientId);

    @POST
    @Path("/process-groups/{id}/process-groups/upload")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    JsonNode uploadFlowDefinition(@PathParam("id") String parentGroupId,
                                  @RestForm String groupName,
                                  @RestForm double positionX,
                                  @RestForm double positionY,
                                  @RestForm String clientId,
                                  @RestForm("file") @PartType(MediaType.APPLICATION_JSON) @PartFilename("flow.json") byte[] file);

    @POST
    @Path("/process-groups/{id}/empty-all-connections-requests")
    JsonNode createEmptyQueuesRequest(@PathParam("id") String id);

    @GET
    @Path("/process-groups/{id}/empty-all-connections-requests/{requestId}")
    JsonNode emptyQueuesRequest(@PathParam("id") String id, @PathParam("requestId") String requestId);

    @DELETE
    @Path("/process-groups/{id}/empty-all-connections-requests/{requestId}")
    JsonNode deleteEmptyQueuesRequest(@PathParam("id") String id, @PathParam("requestId") String requestId);

    @POST
    @Path("/processors/{id}/state/clear-requests")
    JsonNode clearProcessorState(@PathParam("id") String processorId);

    /** Body: {"lineage": {"request": {"lineageRequestType": "FLOWFILE", "uuid": ...}}} */
    @POST
    @Path("/provenance/lineage")
    @Consumes(MediaType.APPLICATION_JSON)
    JsonNode submitLineage(JsonNode body);

    @GET
    @Path("/provenance/lineage/{id}")
    JsonNode lineage(@PathParam("id") String id);

    @DELETE
    @Path("/provenance/lineage/{id}")
    JsonNode deleteLineage(@PathParam("id") String id);

    @GET
    @Path("/provenance-events/{id}")
    JsonNode provenanceEvent(@PathParam("id") String id);
}
