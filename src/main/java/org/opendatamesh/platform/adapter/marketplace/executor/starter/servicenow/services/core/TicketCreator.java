package org.opendatamesh.platform.adapter.marketplace.executor.starter.servicenow.services.core;

import org.opendatamesh.platform.adapter.marketplace.executor.starter.config.ServiceNowConfig;
import org.opendatamesh.platform.adapter.marketplace.executor.starter.config.TicketInterfaceType;
import org.opendatamesh.platform.adapter.marketplace.executor.starter.marketplace.domain.TicketReference;

import java.util.Map;

public interface TicketCreator {

    TicketInterfaceType type();

    TicketReference create(ServiceNowConfig.TicketProfile profile, Map<String, Object> templateData, String correlationId);
}
