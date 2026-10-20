package org.opendatamesh.platform.adapter.marketplace.executor.starter.client.servicenow;

import io.quarkus.qute.Engine;
import io.quarkus.qute.Expression;
import io.quarkus.qute.TemplateNode.Origin;
import io.quarkus.qute.ResultMapper;
import io.quarkus.qute.Results;
import org.junit.jupiter.api.Test;
import org.opendatamesh.platform.adapter.marketplace.executor.starter.config.ServiceNowConfig;
import org.opendatamesh.platform.adapter.marketplace.executor.starter.marketplace.domain.MarketplaceOperation;
import org.opendatamesh.platform.adapter.marketplace.executor.starter.marketplace.domain.MarketplaceRequest;
import org.opendatamesh.platform.adapter.marketplace.executor.starter.marketplace.domain.MarketplaceRequestStatus;
import org.opendatamesh.platform.adapter.marketplace.executor.starter.marketplace.domain.Party;
import org.opendatamesh.platform.adapter.marketplace.executor.starter.marketplace.domain.Provider;
import org.opendatamesh.platform.adapter.marketplace.executor.starter.marketplace.domain.TicketOutcome;
import org.opendatamesh.platform.adapter.marketplace.executor.starter.marketplace.domain.TicketOutcomeCause;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Answers.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

// BDMD-5340: ServiceNow marketplace executor
class ResultMessageServiceTest {

    private static final String TEMPLATE =
            "{outcome.status} via {ticket.number} [{ticket.link}] by:{ticket.closedBy}|{fields.closed_by}|{fields.close_notes}";

    /**
     * Scenario: Closing user is omitted when its inclusion is disabled
     *     Given closing-user inclusion is disabled
     *     And request "id-1" is a registered MARKETPLACE_SUBSCRIBE without final result
     *     When ServiceNow sends a fulfilled ticket event for "id-1" with closed_by "jdoe"
     *     Then the GRANTED result message does not contain "jdoe"
     *     And a template referencing fields.closed_by renders it empty
     */
    @Test
    void whenClosingUserExcludedThenNotExposedToTemplates() {
        Map<String, String> fields = Map.of("number", "INC001", "sys_id", "abc", "table", "incident",
                "closed_by", "jdoe", "close_notes", "done");

        String excluded = finalMessage(false, fields);
        String included = finalMessage(true, fields);

        assertFalse(excluded.contains("jdoe"));
        assertEquals("GRANTED via INC001 [https://acme.service-now.com/incident.do?sys_id=abc] by:||done", excluded);
        assertTrue(included.contains("by:jdoe|jdoe|done"));
    }

    private String finalMessage(boolean includeClosingUser, Map<String, String> fields) {
        ResultMessageService service = new ResultMessageService(config(includeClosingUser),
                new TicketTemplateRenderer(engine()), client());
        return service.finalOutcome(MarketplaceOperation.MARKETPLACE_SUBSCRIBE, request(),
                new TicketOutcome(TicketOutcomeCause.FULFILLED, null), MarketplaceRequestStatus.GRANTED, fields);
    }

    private Engine engine() {
        // equivalent of quarkus.qute.property-not-found-strategy=noop
        return Engine.builder().addDefaults().strictRendering(false)
                .addResultMapper(new ResultMapper() {
                    @Override
                    public boolean appliesTo(Origin origin, Object result) {
                        return Results.isNotFound(result);
                    }

                    @Override
                    public String map(Object result, Expression expression) {
                        return "";
                    }
                }).build();
    }

    private ServiceNowClient client() {
        ServiceNowClient client = mock(ServiceNowClient.class);
        when(client.ticketLink("incident", "abc")).thenReturn("https://acme.service-now.com/incident.do?sys_id=abc");
        return client;
    }

    private ServiceNowConfig config(boolean includeClosingUser) {
        ServiceNowConfig config = mock(ServiceNowConfig.class, RETURNS_DEEP_STUBS);
        ServiceNowConfig.ProfileMessages profileMessages = mock(ServiceNowConfig.ProfileMessages.class);
        when(profileMessages.fulfilled()).thenReturn(Optional.empty());
        ServiceNowConfig.TicketProfile profile = mock(ServiceNowConfig.TicketProfile.class);
        when(profile.messages()).thenReturn(profileMessages);
        when(config.profiles().forOperation(MarketplaceOperation.MARKETPLACE_SUBSCRIBE)).thenReturn(profile);
        when(config.messages().fulfilled()).thenReturn(TEMPLATE);
        when(config.messages().closingUser().include()).thenReturn(includeClosingUser);
        when(config.messages().closingUser().field()).thenReturn("closed_by");
        return config;
    }

    private MarketplaceRequest request() {
        return new MarketplaceRequest("req", "id-1", new Provider("urn:dp:finance:sales", List.of("p1")),
                new Party("USER", "c"), new Party("USER", "r"), Map.of(), null, null);
    }
}
