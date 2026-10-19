package org.opendatamesh.platform.adapter.marketplace.executor.starter.marketplace.services.usecases.completeticket;

import org.opendatamesh.platform.adapter.marketplace.executor.starter.marketplace.services.core.MarketplaceServiceClient;
import org.opendatamesh.platform.adapter.marketplace.executor.starter.servicenow.services.core.OutcomeRulesService;
import org.opendatamesh.platform.adapter.marketplace.executor.starter.servicenow.services.core.RequestFilterService;
import org.opendatamesh.platform.adapter.marketplace.executor.starter.servicenow.services.core.ResultMessageService;
import org.opendatamesh.platform.adapter.marketplace.executor.starter.utils.usecases.UseCase;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class CompleteTicketFactory {

    private final MarketplaceServiceClient marketplaceServiceClient;
    private final RequestFilterService requestFilterService;
    private final OutcomeRulesService outcomeRulesService;
    private final ResultMessageService resultMessageService;

    public CompleteTicketFactory(
            MarketplaceServiceClient marketplaceServiceClient,
            RequestFilterService requestFilterService,
            OutcomeRulesService outcomeRulesService,
            ResultMessageService resultMessageService
    ) {
        this.marketplaceServiceClient = marketplaceServiceClient;
        this.requestFilterService = requestFilterService;
        this.outcomeRulesService = outcomeRulesService;
        this.resultMessageService = resultMessageService;
    }

    public UseCase buildCompleteTicket(CompleteTicketCommand command, CompleteTicketPresenter presenter) {
        return new CompleteTicket(
                command,
                presenter,
                new CompleteTicketMarketplaceOutboundPortImpl(marketplaceServiceClient),
                new CompleteTicketFilterOutboundPortImpl(requestFilterService),
                new TicketOutcomeOutboundPortImpl(outcomeRulesService),
                new CompleteTicketMessageOutboundPortImpl(resultMessageService)
        );
    }
}
