package org.opendatamesh.platform.adapter.marketplace.executor.starter.servicenow.services.core;

import jakarta.enterprise.context.ApplicationScoped;
import org.opendatamesh.platform.adapter.marketplace.executor.starter.config.ServiceNowConfig;
import org.opendatamesh.platform.adapter.marketplace.executor.starter.marketplace.domain.MarketplaceOperation;
import org.opendatamesh.platform.adapter.marketplace.executor.starter.marketplace.domain.MarketplaceRequest;
import org.opendatamesh.platform.adapter.marketplace.executor.starter.marketplace.domain.MarketplaceRequestStatus;
import org.opendatamesh.platform.adapter.marketplace.executor.starter.marketplace.domain.TicketOutcome;
import org.opendatamesh.platform.adapter.marketplace.executor.starter.marketplace.domain.TicketReference;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

@ApplicationScoped
public class ResultMessageService {

    private static final int MAX_MESSAGE_LENGTH = 4000;

    private final ServiceNowConfig config;
    private final TicketTemplateRenderer renderer;
    private final ServiceNowClient serviceNowClient;

    public ResultMessageService(ServiceNowConfig config, TicketTemplateRenderer renderer, ServiceNowClient serviceNowClient) {
        this.config = config;
        this.renderer = renderer;
        this.serviceNowClient = serviceNowClient;
    }

    public String pending(MarketplaceOperation operation, MarketplaceRequest request, TicketReference ticket) {
        Map<String, Object> data = renderer.requestData(operation, request);
        data.put("ticket", ticketData(ticket.number(), ticket.sysId(), ticket.table(), ticket.link(), null));
        String template = chooseTemplate(operation, null, ServiceNowConfig.ProfileMessages::pending, ServiceNowConfig.Messages::pending);
        return finish(renderer.render(template, data));
    }

    public String finalOutcome(MarketplaceOperation operation, MarketplaceRequest request, TicketOutcome outcome,
                               MarketplaceRequestStatus status, Map<String, String> fields) {
        Map<String, Object> data = renderer.requestData(operation, request);
        data.put("ticket", ticketDataFromFields(fields));
        data.put("fields", exposedFields(fields));
        data.put("outcome", Map.of("cause", outcome.cause().name(), "status", status.name()));
        String template = chooseTemplate(operation, outcome.messageTemplate(), causeProfileMessage(outcome), causeGlobalMessage(outcome));
        return finish(renderer.render(template, data));
    }

    public String technicalError(MarketplaceOperation operation, MarketplaceRequest request, String errorMessage) {
        Map<String, Object> data = renderer.requestData(operation, request);
        data.put("error", Map.of("message", errorMessage == null ? "" : errorMessage));
        String template = chooseTemplate(operation, null, ServiceNowConfig.ProfileMessages::technicalError, ServiceNowConfig.Messages::technicalError);
        return finish(renderer.render(template, data));
    }

    private String chooseTemplate(MarketplaceOperation operation, String ruleMessage,
                                  Function<ServiceNowConfig.ProfileMessages, Optional<String>> profileMessage,
                                  Function<ServiceNowConfig.Messages, String> globalMessage) {
        if (ruleMessage != null) {
            return ruleMessage;
        }
        return profileMessage.apply(config.profiles().forOperation(operation).messages())
                .orElseGet(() -> globalMessage.apply(config.messages()));
    }

    private Function<ServiceNowConfig.ProfileMessages, Optional<String>> causeProfileMessage(TicketOutcome outcome) {
        return switch (outcome.cause()) {
            case FULFILLED -> ServiceNowConfig.ProfileMessages::fulfilled;
            case REFUSED -> ServiceNowConfig.ProfileMessages::refused;
            case FAILED -> ServiceNowConfig.ProfileMessages::failed;
        };
    }

    private Function<ServiceNowConfig.Messages, String> causeGlobalMessage(TicketOutcome outcome) {
        return switch (outcome.cause()) {
            case FULFILLED -> ServiceNowConfig.Messages::fulfilled;
            case REFUSED -> ServiceNowConfig.Messages::refused;
            case FAILED -> ServiceNowConfig.Messages::failed;
        };
    }

    private Map<String, Object> ticketDataFromFields(Map<String, String> fields) {
        String table = fields.get("table");
        String sysId = fields.get("sys_id");
        String link = serviceNowClient.ticketLink(table, sysId);
        return ticketData(fields.get("number"), sysId, table, link, fields.get(closingUserField()));
    }

    private Map<String, Object> ticketData(String number, String sysId, String table, String link, String closedBy) {
        Map<String, Object> ticket = new LinkedHashMap<>();
        ticket.put("number", orEmpty(number));
        ticket.put("sysId", orEmpty(sysId));
        ticket.put("table", orEmpty(table));
        ticket.put("link", orEmpty(link));
        if (closingUserIncluded() && closedBy != null) {
            ticket.put("closedBy", closedBy);
        }
        return ticket;
    }

    private Map<String, String> exposedFields(Map<String, String> fields) {
        Map<String, String> exposed = new LinkedHashMap<>(fields);
        if (!closingUserIncluded()) {
            exposed.remove(closingUserField());
        }
        return exposed;
    }

    private boolean closingUserIncluded() {
        return config.messages().closingUser().include();
    }

    private String closingUserField() {
        return config.messages().closingUser().field();
    }

    private String finish(String message) {
        String trimmed = message.trim();
        return trimmed.length() <= MAX_MESSAGE_LENGTH ? trimmed : trimmed.substring(0, MAX_MESSAGE_LENGTH);
    }

    private String orEmpty(String value) {
        return value == null ? "" : value;
    }
}
