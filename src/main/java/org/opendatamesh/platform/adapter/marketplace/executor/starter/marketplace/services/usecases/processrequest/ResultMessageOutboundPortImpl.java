package org.opendatamesh.platform.adapter.marketplace.executor.starter.marketplace.services.usecases.processrequest;

import org.opendatamesh.platform.adapter.marketplace.executor.starter.marketplace.domain.MarketplaceOperation;
import org.opendatamesh.platform.adapter.marketplace.executor.starter.marketplace.domain.MarketplaceRequest;
import org.opendatamesh.platform.adapter.marketplace.executor.starter.marketplace.domain.TicketReference;
import org.opendatamesh.platform.adapter.marketplace.executor.starter.client.servicenow.ResultMessageService;

class ResultMessageOutboundPortImpl implements ResultMessageOutboundPort {

    private final ResultMessageService messageService;

    ResultMessageOutboundPortImpl(ResultMessageService messageService) {
        this.messageService = messageService;
    }

    @Override
    public String pendingMessage(MarketplaceOperation operation, MarketplaceRequest request, TicketReference ticket) {
        return messageService.pending(operation, request, ticket);
    }

    @Override
    public String technicalErrorMessage(MarketplaceOperation operation, MarketplaceRequest request, String error) {
        return messageService.technicalError(operation, request, error);
    }
}
