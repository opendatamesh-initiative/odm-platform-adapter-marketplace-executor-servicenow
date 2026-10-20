package org.opendatamesh.platform.adapter.marketplace.executor.starter.marketplace.services.usecases.processrequest;

import org.opendatamesh.platform.adapter.marketplace.executor.starter.marketplace.domain.MarketplaceOperation;
import org.opendatamesh.platform.adapter.marketplace.executor.starter.marketplace.domain.MarketplaceRequest;
import org.opendatamesh.platform.adapter.marketplace.executor.starter.marketplace.domain.TicketReference;
import org.opendatamesh.platform.adapter.marketplace.executor.starter.client.servicenow.ServiceNowTicketService;

class ServiceNowTicketOutboundPortImpl implements ServiceNowTicketOutboundPort {

    private final ServiceNowTicketService ticketService;

    ServiceNowTicketOutboundPortImpl(ServiceNowTicketService ticketService) {
        this.ticketService = ticketService;
    }

    @Override
    public TicketReference openTicket(MarketplaceOperation operation, MarketplaceRequest request) {
        return ticketService.openTicket(operation, request);
    }
}
