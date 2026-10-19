package org.opendatamesh.platform.adapter.marketplace.executor.starter.marketplace.services.usecases.processrequest;

import org.opendatamesh.platform.adapter.marketplace.executor.starter.marketplace.services.core.MarketplaceServiceClient;
import org.opendatamesh.platform.adapter.marketplace.executor.starter.servicenow.services.core.RequestFilterService;
import org.opendatamesh.platform.adapter.marketplace.executor.starter.servicenow.services.core.ResultMessageService;
import org.opendatamesh.platform.adapter.marketplace.executor.starter.servicenow.services.core.ServiceNowTicketService;
import org.opendatamesh.platform.adapter.marketplace.executor.starter.utils.services.programmatic.AsyncExecutorService;
import org.opendatamesh.platform.adapter.marketplace.executor.starter.utils.usecases.UseCase;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class ProcessMarketplaceRequestFactory {

    private final RequestFilterService requestFilterService;
    private final ServiceNowTicketService serviceNowTicketService;
    private final ResultMessageService resultMessageService;
    private final MarketplaceServiceClient marketplaceServiceClient;
    private final AsyncExecutorService asyncExecutorService;

    public ProcessMarketplaceRequestFactory(
            RequestFilterService requestFilterService,
            ServiceNowTicketService serviceNowTicketService,
            ResultMessageService resultMessageService,
            MarketplaceServiceClient marketplaceServiceClient,
            AsyncExecutorService asyncExecutorService
    ) {
        this.requestFilterService = requestFilterService;
        this.serviceNowTicketService = serviceNowTicketService;
        this.resultMessageService = resultMessageService;
        this.marketplaceServiceClient = marketplaceServiceClient;
        this.asyncExecutorService = asyncExecutorService;
    }

    public UseCase buildProcessMarketplaceRequest(ProcessMarketplaceRequestCommand command) {
        return new ProcessMarketplaceRequest(
                command,
                new RequestFilterOutboundPortImpl(requestFilterService),
                new ServiceNowTicketOutboundPortImpl(serviceNowTicketService),
                new ResultMessageOutboundPortImpl(resultMessageService),
                new MarketplaceResultOutboundPortImpl(marketplaceServiceClient),
                new AsyncExecutionOutboundPortImpl(asyncExecutorService)
        );
    }
}
