package org.opendatamesh.platform.adapter.marketplace.executor.starter.client.servicenow;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.opendatamesh.platform.adapter.marketplace.executor.starter.config.ServiceNowConfig;
import org.opendatamesh.platform.adapter.marketplace.executor.starter.exceptions.InternalException;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

// BDMD-5340: ServiceNow marketplace executor
class ScriptedRestTicketCreatorTest {

    /**
     * Scenario: Scripted REST response without the configured number pointer is reported as ERROR
     *   Given the subscribe profile uses a Scripted REST endpoint with number pointer "/result/ticket"
     *   And the endpoint answers without "/result/ticket"
     *   When a subscribe request "id-5" is processed
     *   Then an ERROR result explaining that the ticket number was not found is posted for request "id-5"
     */
    @Test
    void whenNumberPointerMissingThenTicketCreationFails() throws Exception {
        ServiceNowClient client = mock(ServiceNowClient.class);
        TicketTemplateRenderer renderer = mock(TicketTemplateRenderer.class);
        when(client.send(eq("POST"), eq("/api/x_acme/marketplace/ticket"), any()))
                .thenReturn(new ObjectMapper().readTree("{\"result\":{\"other\":\"x\"}}"));
        ServiceNowConfig.TicketProfile profile = mock(ServiceNowConfig.TicketProfile.class, org.mockito.Answers.RETURNS_DEEP_STUBS);
        when(profile.path()).thenReturn(Optional.of("/api/x_acme/marketplace/ticket"));
        when(profile.method()).thenReturn("POST");
        when(profile.fields()).thenReturn(Map.of());
        when(profile.correlationField()).thenReturn(Optional.empty());
        when(profile.response().numberPointer()).thenReturn(Optional.of("/result/ticket"));
        when(profile.response().sysIdPointer()).thenReturn(Optional.of("/result/sys_id"));
        when(profile.response().tablePointer()).thenReturn(Optional.empty());
        when(profile.response().table()).thenReturn(Optional.empty());

        ScriptedRestTicketCreator creator = new ScriptedRestTicketCreator(client, renderer, mock(ServiceNowConfig.class));

        InternalException e = assertThrows(InternalException.class, () -> creator.create(profile, Map.of(), "id-5"));
        assertTrue(e.getMessage().contains("ticket number"));
        assertTrue(e.getMessage().contains("/result/ticket"));
    }
}
