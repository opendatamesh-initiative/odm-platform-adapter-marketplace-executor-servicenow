package org.opendatamesh.platform.adapter.marketplace.executor.starter.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.opendatamesh.platform.adapter.marketplace.executor.starter.utils.client.RestUtils;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

// BDMD-5340: ServiceNow marketplace executor
class ServiceNowRestUtilsProducerTest {

    private HttpServer server;
    private final AtomicReference<String> tokenRequestBody = new AtomicReference<>();
    private final AtomicReference<String> apiAuthorization = new AtomicReference<>();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/oauth_token.do", exchange -> {
            tokenRequestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            reply(exchange, "{\"access_token\":\"tok-123\",\"expires_in\":1800}");
        });
        server.createContext("/api/now/table/incident", exchange -> {
            apiAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            reply(exchange, "{\"result\":[]}");
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    /**
     * Scenario: OAuth client credentials authenticate ServiceNow calls
     *   Given the executor is configured with auth type OAUTH2_CLIENT_CREDENTIALS
     *   When the ServiceNow client is produced
     *   Then it requests a token from "<instance>/oauth_token.do" with grant type "client_credentials"
     *   And it sends ServiceNow calls with a Bearer token
     */
    @Test
    void whenOauthClientCredentialsThenBearerTokenRequested() {
        String instance = "http://localhost:" + server.getAddress().getPort();
        ServiceNowConfig config = oauthConfig(instance, Optional.empty());

        RestUtils restUtils = new ServiceNowRestUtilsProducer().serviceNowRestUtils(config, new ObjectMapper());
        restUtils.genericGet(instance + "/api/now/table/incident", List.of(), null, JsonNode.class);

        assertTrue(tokenRequestBody.get().contains("grant_type=client_credentials"));
        assertTrue(tokenRequestBody.get().contains("client_id=cid"));
        assertEquals("Bearer tok-123", apiAuthorization.get());
        // Operation 6a item 4: the optional scope is omitted when not configured
        assertFalse(tokenRequestBody.get().contains("scope"));
    }

    private ServiceNowConfig oauthConfig(String instance, Optional<String> scope) {
        ServiceNowConfig config = mock(ServiceNowConfig.class, org.mockito.Answers.RETURNS_DEEP_STUBS);
        when(config.instanceUrl()).thenReturn(instance);
        when(config.auth().type()).thenReturn(ServiceNowAuthType.OAUTH2_CLIENT_CREDENTIALS);
        when(config.auth().oauth2().clientId()).thenReturn(Optional.of("cid"));
        when(config.auth().oauth2().clientSecret()).thenReturn(Optional.of("csecret"));
        when(config.auth().oauth2().tokenUrl()).thenReturn(Optional.empty());
        when(config.auth().oauth2().scope()).thenReturn(scope);
        return config;
    }

    private void reply(com.sun.net.httpserver.HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
