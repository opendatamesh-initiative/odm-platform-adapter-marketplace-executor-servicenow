package org.opendatamesh.platform.adapter.marketplace.executor.starter.servicenow.services.core;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import org.jboss.logging.Logger;
import org.opendatamesh.platform.adapter.marketplace.executor.starter.config.ServiceNowConfig;
import org.opendatamesh.platform.adapter.marketplace.executor.starter.config.TicketInterfaceType;
import org.opendatamesh.platform.adapter.marketplace.executor.starter.exceptions.InternalException;
import org.opendatamesh.platform.adapter.marketplace.executor.starter.marketplace.domain.MarketplaceOperation;
import org.opendatamesh.platform.adapter.marketplace.executor.starter.marketplace.domain.MarketplaceRequest;
import org.opendatamesh.platform.adapter.marketplace.executor.starter.marketplace.domain.TicketReference;

import java.util.EnumMap;
import java.util.Map;

@ApplicationScoped
public class ServiceNowTicketService {

    private static final Logger log = Logger.getLogger(ServiceNowTicketService.class);

    private final ServiceNowConfig config;
    private final TicketTemplateRenderer renderer;
    private final Map<TicketInterfaceType, TicketCreator> creators = new EnumMap<>(TicketInterfaceType.class);

    public ServiceNowTicketService(ServiceNowConfig config, TicketTemplateRenderer renderer, Instance<TicketCreator> creators) {
        this.config = config;
        this.renderer = renderer;
        creators.forEach(creator -> this.creators.put(creator.type(), creator));
    }

    public TicketReference openTicket(MarketplaceOperation operation, MarketplaceRequest request) {
        ServiceNowConfig.TicketProfile profile = config.profiles().forOperation(operation);
        Map<String, Object> data = renderer.requestData(operation, request);
        TicketReference ticket = creatorFor(profile).create(profile, data, request.identifier());
        log.infof("Opened ServiceNow ticket %s (%s) for request %s operation %s",
                ticket.number(), ticket.table(), request.identifier(), operation);
        return ticket;
    }

    private TicketCreator creatorFor(ServiceNowConfig.TicketProfile profile) {
        TicketCreator creator = creators.get(profile.type());
        if (creator == null) {
            throw new InternalException("No ticket creator available for interface type " + profile.type());
        }
        return creator;
    }
}
