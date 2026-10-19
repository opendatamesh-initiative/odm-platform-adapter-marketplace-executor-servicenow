package org.opendatamesh.platform.adapter.marketplace.executor.starter.client.servicenow;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.opendatamesh.platform.adapter.marketplace.executor.starter.config.ServiceNowAuthType;
import org.opendatamesh.platform.adapter.marketplace.executor.starter.config.ServiceNowConfig;
import org.opendatamesh.platform.adapter.marketplace.executor.starter.exceptions.InternalException;
import org.opendatamesh.platform.adapter.marketplace.executor.starter.exceptions.client.ClientException;
import org.opendatamesh.platform.adapter.marketplace.executor.starter.utils.client.RestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

// BDMD-5340: ServiceNow marketplace executor
class ServiceNowClientTest {

    private RestUtils restUtils;
    private ServiceNowClient client;

    @BeforeEach
    void setUp() {
        restUtils = mock(RestUtils.class);
        ServiceNowConfig config = mock(ServiceNowConfig.class, org.mockito.Answers.RETURNS_DEEP_STUBS);
        when(config.instanceUrl()).thenReturn("https://acme.service-now.com/");
        when(config.auth().type()).thenReturn(ServiceNowAuthType.API_KEY);
        client = new ServiceNowClient(restUtils, config);
    }

    /**
     * Scenario: ServiceNow rejects the integration credentials
     *   Given ServiceNow answers 401 to ticket creation
     *   When a subscribe request "id-6" is processed
     *   Then an ERROR result mentioning the integration credentials and access policy is posted for request "id-6"
     *   And no secret value appears in the message
     */
    @Test
    void whenUnauthorizedThenDescriptiveErrorWithoutSecrets() {
        when(restUtils.genericPost(anyString(), any(), any(), any()))
                .thenThrow(new ClientException(401, "{\"error\":\"secret-body-token\"}"));

        InternalException e = assertThrows(InternalException.class,
                () -> client.send("POST", "/api/now/table/incident", null));

        assertTrue(e.getMessage().contains("integration credentials"));
        assertTrue(e.getMessage().contains("access policy"));
        assertTrue(e.getMessage().contains("API_KEY"));
        assertFalse(e.getMessage().contains("secret-body-token"));
    }

    /**
     * Scenario: OAuth token cannot be obtained
     *   Given the OAuth token endpoint rejects the client credentials
     *   When a subscribe request "id-7" is processed
     *   Then an ERROR result explaining that a ServiceNow OAuth token could not be obtained is posted for request "id-7"
     */
    @Test
    void whenOauthTokenUnavailableThenDescriptiveError() {
        when(restUtils.genericGet(anyString(), any(), any(), any()))
                .thenThrow(new IllegalStateException("invalid_client"));

        InternalException e = assertThrows(InternalException.class,
                () -> client.get("/api/now/table/sys_user", java.util.Map.of("sysparm_limit", "1")));

        assertTrue(e.getMessage().contains("Unable to obtain a ServiceNow OAuth token"));
        assertTrue(e.getMessage().contains("invalid_client"));
    }
}
