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
class ImportSetTicketCreatorTest {

    /**
     * Scenario: Import Set transform error is reported as ERROR
     *   Given the Import Set API answers with row status "error" and error message "Transform failed"
     *   When the Marketplace Service submits a MARKETPLACE_UNSUBSCRIBE request "id-3"
     *   Then an ERROR result whose message contains "Transform failed" is posted for request "id-3"
     */
    @Test
    void whenRowStatusErrorThenTicketCreationFails() throws Exception {
        ServiceNowClient client = mock(ServiceNowClient.class);
        TicketTemplateRenderer renderer = mock(TicketTemplateRenderer.class);
        when(client.send(eq("POST"), eq("/api/now/import/u_marketplace_revoke"), any())).thenReturn(new ObjectMapper()
                .readTree("{\"result\":[{\"status\":\"error\",\"error_message\":\"Transform failed\"}]}"));
        ServiceNowConfig.TicketProfile profile = mock(ServiceNowConfig.TicketProfile.class, org.mockito.Answers.RETURNS_DEEP_STUBS);
        when(profile.target()).thenReturn(Optional.of("u_marketplace_revoke"));
        when(profile.fields()).thenReturn(Map.of());
        when(profile.correlationField()).thenReturn(Optional.empty());
        when(profile.response().numberPointer()).thenReturn(Optional.empty());
        when(profile.response().sysIdPointer()).thenReturn(Optional.empty());
        when(profile.response().tablePointer()).thenReturn(Optional.empty());
        when(profile.response().table()).thenReturn(Optional.empty());

        ImportSetTicketCreator creator = new ImportSetTicketCreator(client, renderer, mock(ServiceNowConfig.class));

        InternalException e = assertThrows(InternalException.class, () -> creator.create(profile, Map.of(), "id-3"));
        assertTrue(e.getMessage().contains("Transform failed"));
    }
}
