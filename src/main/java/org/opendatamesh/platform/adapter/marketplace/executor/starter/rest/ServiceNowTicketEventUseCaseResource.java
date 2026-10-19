package org.opendatamesh.platform.adapter.marketplace.executor.starter.rest;

import org.opendatamesh.platform.adapter.marketplace.executor.starter.marketplace.services.MarketplaceExecutorUseCasesService;
import org.opendatamesh.platform.adapter.marketplace.executor.starter.resources.TicketEventCommandRes;
import org.opendatamesh.platform.adapter.marketplace.executor.starter.resources.TicketEventResultRes;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.parameters.RequestBody;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

@Path("/api/v1/up/marketplace-executor/webhooks/servicenow")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "ServiceNow Ticket Events", description = "Webhook called by ServiceNow (through the blindata-agent proxy) when a ticket changes state")
public class ServiceNowTicketEventUseCaseResource {

    private final MarketplaceExecutorUseCasesService useCasesService;

    public ServiceNowTicketEventUseCaseResource(MarketplaceExecutorUseCasesService useCasesService) {
        this.useCasesService = useCasesService;
    }

    @POST
    @Path("/ticket-events")
    @Operation(summary = "Receive a ServiceNow ticket event", description = "Reports the final result of the marketplace request when the ticket reached a terminal state, otherwise ignores the event")
    @APIResponses(value = {
            @APIResponse(responseCode = "200", description = "Event reported to the Marketplace Service or ignored"),
            @APIResponse(responseCode = "500", description = "Marketplace or ServiceNow processing failure, ServiceNow should retry")
    })
    public TicketEventResultRes receiveTicketEvent(
            @RequestBody(description = "Ticket event details", required = true) TicketEventCommandRes body) {
        return useCasesService.processTicketEvent(body);
    }
}
