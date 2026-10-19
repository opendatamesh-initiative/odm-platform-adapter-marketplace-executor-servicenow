package org.opendatamesh.platform.adapter.marketplace.executor.starter.servicenow.services.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.opendatamesh.platform.adapter.marketplace.executor.starter.config.ServiceNowConfig;
import org.opendatamesh.platform.adapter.marketplace.executor.starter.exceptions.InternalException;
import org.opendatamesh.platform.adapter.marketplace.executor.starter.marketplace.domain.TicketReference;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// BDMD-5340: ServiceNow marketplace executor
class ServiceCatalogTicketCreatorTest {

    private static final String ORDER_PATH = "/api/sn_sc/servicecatalog/items/item-1/order_now";
    private static final String SUBMIT_PATH = "/api/sn_sc/servicecatalog/cart/submit_order";

    private final ObjectMapper mapper = new ObjectMapper();
    private ServiceNowClient client;
    private ServiceNowConfig.TicketProfile profile;
    private ServiceCatalogTicketCreator creator;

    @BeforeEach
    void setUp() {
        client = mock(ServiceNowClient.class);
        TicketTemplateRenderer renderer = mock(TicketTemplateRenderer.class);
        when(renderer.render(anyString(), any())).thenAnswer(a -> a.getArgument(0));
        when(client.ticketLink(any(), any())).thenReturn("link");
        profile = mock(ServiceNowConfig.TicketProfile.class, org.mockito.Answers.RETURNS_DEEP_STUBS);
        when(profile.target()).thenReturn(Optional.of("item-1"));
        when(profile.fields()).thenReturn(Map.of("justification", "needed"));
        when(profile.correlationField()).thenReturn(Optional.empty());
        when(profile.requesterMatchField()).thenReturn("email");
        when(profile.submitCartWhenTwoStep()).thenReturn(true);
        when(profile.response().numberPointer()).thenReturn(Optional.empty());
        when(profile.response().sysIdPointer()).thenReturn(Optional.empty());
        when(profile.response().tablePointer()).thenReturn(Optional.empty());
        when(profile.response().table()).thenReturn(Optional.empty());
        creator = new ServiceCatalogTicketCreator(client, renderer, mock(ServiceNowConfig.class));
    }

    /**
     * Scenario: Service Catalog two-step checkout submits the cart
     *   Given the subscribe profile uses the Service Catalog API
     *   And order_now answers with a cart_id and no request number
     *   When a subscribe request "id-4" is processed
     *   Then the cart is submitted
     *   And the PENDING result references the request number returned by submit_order
     */
    @Test
    void whenOrderNowReturnsCartThenCartSubmitted() throws Exception {
        requesterExists("jane@acme.com", "u-42");
        when(client.send(eq("POST"), eq(ORDER_PATH), any())).thenReturn(json("{\"result\":{\"cart_id\":\"c1\"}}"));
        when(client.send(eq("POST"), eq(SUBMIT_PATH), any()))
                .thenReturn(json("{\"result\":{\"request_number\":\"REQ0001\",\"request_id\":\"r1\"}}"));

        TicketReference ticket = creator.create(profile, data("user", "jane@acme.com"), "id-4");

        verify(client).send(eq("POST"), eq(SUBMIT_PATH), any());
        assertEquals("REQ0001", ticket.number());
        assertEquals("r1", ticket.sysId());
        assertEquals("sc_request", ticket.table());
    }

    /**
     * Scenario: Catalog order is placed on behalf of the marketplace requester
     *   Given the subscribe profile uses the Service Catalog API
     *   And the requester "jane@acme.com" exists in ServiceNow sys_user with sys_id "u-42"
     *   When a subscribe request "id-8" requested by "jane@acme.com" is processed
     *   Then the catalog order is sent with requested_for "u-42"
     */
    @Test
    void whenRequesterFoundThenOrderRequestedForRequester() throws Exception {
        requesterExists("jane@acme.com", "u-42");
        when(client.send(eq("POST"), eq(ORDER_PATH), any()))
                .thenReturn(json("{\"result\":{\"request_number\":\"REQ0002\",\"request_id\":\"r2\"}}"));

        creator.create(profile, data("user", "jane@acme.com"), "id-8");

        ArgumentCaptor<ObjectNode> body = ArgumentCaptor.forClass(ObjectNode.class);
        verify(client).send(eq("POST"), eq(ORDER_PATH), body.capture());
        assertEquals("u-42", body.getValue().get("sysparm_requested_for").asText());
        assertEquals("1", body.getValue().get("sysparm_quantity").asText());
        assertEquals("id-8", body.getValue().at("/variables/marketplace_request_id").asText());
        assertEquals("needed", body.getValue().at("/variables/justification").asText());
        verify(client, never()).send(eq("POST"), eq(SUBMIT_PATH), any());
    }

    /**
     * Scenario: Unknown requester fails the catalog order
     *   Given the subscribe profile uses the Service Catalog API
     *   And the requester "ghost@acme.com" does not exist in ServiceNow sys_user
     *   When a subscribe request "id-9" requested by "ghost@acme.com" is processed
     *   Then no catalog order is sent
     *   And an ERROR result stating that the requester was not found is posted for request "id-9"
     */
    @Test
    void whenRequesterNotFoundThenTicketCreationFails() throws Exception {
        when(client.get(eq("/api/now/table/sys_user"), any())).thenReturn(json("{\"result\":[]}"));

        InternalException e = assertThrows(InternalException.class,
                () -> creator.create(profile, data("user", "ghost@acme.com"), "id-9"));

        assertTrue(e.getMessage().contains("ghost@acme.com"));
        assertTrue(e.getMessage().contains("not found"));
        verify(client, never()).send(anyString(), anyString(), any());
    }

    private void requesterExists(String identifier, String sysId) throws Exception {
        when(client.get(eq("/api/now/table/sys_user"), eq(Map.of(
                "sysparm_query", "email=" + identifier,
                "sysparm_fields", "sys_id",
                "sysparm_limit", "1"))))
                .thenReturn(json("{\"result\":[{\"sys_id\":\"" + sysId + "\"}]}"));
    }

    private JsonNode json(String text) throws Exception {
        return mapper.readTree(text);
    }

    private Map<String, Object> data(String requesterType, String requesterIdentifier) {
        return Map.of("request", Map.of("requester", Map.of("type", requesterType, "identifier", requesterIdentifier)));
    }
}
