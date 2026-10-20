package org.opendatamesh.platform.adapter.marketplace.executor.starter.rest.v1.controllers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.opendatamesh.platform.adapter.marketplace.executor.starter.config.ServiceNowApi;
import org.opendatamesh.platform.adapter.marketplace.executor.starter.exceptions.client.ClientException;
import org.opendatamesh.platform.adapter.marketplace.executor.starter.marketplace.domain.MarketplaceRequestStatus;
import org.opendatamesh.platform.adapter.marketplace.executor.starter.rest.v1.resources.marketplace.MarketplaceResponseRes;
import org.opendatamesh.platform.adapter.marketplace.executor.starter.utils.client.RestUtils;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.endsWith;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

// BDMD-5340: ServiceNow marketplace executor
@QuarkusTest
class ServiceNowTicketEventUseCaseControllerTest {

    private static final String PATH = "/api/v1/up/marketplace-executor/webhooks/servicenow/ticket-events";

    @InjectMock
    RestUtils restUtils;

    @InjectMock
    @ServiceNowApi
    RestUtils serviceNowRestUtils;

    private final ObjectMapper mapper = new ObjectMapper();

    private void registeredRequest(String uuid, String operation, boolean finalResultExists) throws Exception {
        JsonNode registered = mapper.readTree("""
                {"uuid":"%s","operation":"%s","name":"n","providerDataProductFqn":"urn:dp:finance:sales",
                 "providerDataProductPortsFqn":["p1","p2"],"consumerType":"user","consumerIdentifier":"a@b.c",
                 "requesterType":"user","requesterIdentifier":"a@b.c","properties":{}}
                """.formatted(uuid, operation));
        JsonNode responses = mapper.readTree("{\"content\":[{\"status\":\"PENDING\"}" + (finalResultExists ? ",{\"status\":\"GRANTED\"}" : "") + "]}");
        when(restUtils.genericGet(endsWith("/api/v1/pp/marketplace/requests/" + uuid), any(), any(), eq(JsonNode.class))).thenReturn(registered);
        when(restUtils.genericGet(contains("/executor-responses"), any(), any(), eq(JsonNode.class))).thenReturn(responses);
    }

    private String event(String correlation, String state, String closeCode) {
        return """
                {%s"fields":{"state":"%s","close_code":"%s","number":"INC0010001","sys_id":"abc","table":"incident"}}
                """.formatted(correlation == null ? "" : "\"correlationId\":\"" + correlation + "\",", state, closeCode);
    }

    /**
     * Scenario: Fulfilled subscribe ticket grants access for all ports
     *   Given request "id-1" is a MARKETPLACE_SUBSCRIBE registered in the Marketplace Service with ports "p1" and "p2"
     *   And no final result exists for "id-1"
     *   When ServiceNow sends a ticket event for "id-1" with state "7" and close_code "Solved (Permanently)"
     *   Then a GRANTED result with ports "p1" and "p2" is posted for "id-1"
     *   And the webhook answers 200 with outcome "REPORTED" and status "GRANTED"
     */
    @Test
    void whenSubscribeTicketFulfilledThenGrantedWithAllPorts() throws Exception {
        registeredRequest("id-1", "MARKETPLACE_SUBSCRIBE", false);

        given().contentType(ContentType.JSON).body(event("id-1", "7", "Solved (Permanently)"))
                .when().post(PATH)
                .then().statusCode(200)
                .body("outcome", equalTo("REPORTED"))
                .body("status", equalTo("GRANTED"))
                .body("reason", nullValue());

        ArgumentCaptor<Object> result = ArgumentCaptor.forClass(Object.class);
        verify(restUtils).genericPost(endsWith("/api/v1/pp/marketplace/requests/id-1/results"), any(), result.capture(), eq(Void.class));
        MarketplaceResponseRes posted = (MarketplaceResponseRes) result.getValue();
        assertEquals(MarketplaceRequestStatus.GRANTED, posted.getStatus());
        assertEquals(List.of("p1", "p2"), posted.getProvider().getDataProductPortsFqn());
        assertTrue(posted.getMessage().contains("INC0010001"));
    }

    /**
     * Scenario: First final status wins
     *   Given request "id-1" already has a GRANTED result in the Marketplace Service
     *   When ServiceNow sends another ticket event for "id-1" matching a REFUSED rule
     *   Then no result is posted
     *   And the webhook answers 200 with outcome "IGNORED" and reason "FINAL_RESULT_ALREADY_REPORTED"
     */
    @Test
    void whenFinalResultExistsThenEventIgnored() throws Exception {
        registeredRequest("id-1", "MARKETPLACE_SUBSCRIBE", true);

        given().contentType(ContentType.JSON).body(event("id-1", "8", ""))
                .when().post(PATH)
                .then().statusCode(200)
                .body("outcome", equalTo("IGNORED"))
                .body("reason", equalTo("FINAL_RESULT_ALREADY_REPORTED"))
                .body("status", nullValue());

        verify(restUtils, never()).genericPost(any(), any(), any(), any());
    }

    /**
     * Scenario: Missing correlation is ignored
     *   When ServiceNow sends a ticket event without correlationId
     *   Then no result is posted and the reason is "MISSING_CORRELATION"
     */
    @Test
    void whenCorrelationMissingThenIgnored() {
        given().contentType(ContentType.JSON).body(event(null, "7", "Solved (Permanently)"))
                .when().post(PATH)
                .then().statusCode(200)
                .body("outcome", equalTo("IGNORED"))
                .body("reason", equalTo("MISSING_CORRELATION"));

        verifyNoInteractions(restUtils);
    }

    /**
     * Scenario: Missing correlation is ignored
     *   When ServiceNow sends a ticket event without correlationId
     *   Then no result is posted and the reason is "MISSING_CORRELATION"
     * (variant: empty request body)
     */
    @Test
    void whenBodyEmptyThenIgnoredAsMissingCorrelation() {
        given().contentType(ContentType.JSON)
                .when().post(PATH)
                .then().statusCode(200)
                .body("outcome", equalTo("IGNORED"))
                .body("reason", equalTo("MISSING_CORRELATION"));

        verifyNoInteractions(restUtils);
    }

    /**
     * Scenario: Marketplace unavailable makes the webhook fail for retry
     *   Given request "id-1" is a registered MARKETPLACE_SUBSCRIBE without final result
     *   And the Marketplace Service results endpoint keeps answering 503
     *   When ServiceNow sends a fulfilled ticket event for "id-1"
     *   Then the webhook answers 500
     */
    @Test
    void whenMarketplaceUnavailableThenServerError() throws Exception {
        registeredRequest("id-1", "MARKETPLACE_SUBSCRIBE", false);
        when(restUtils.genericPost(endsWith("/api/v1/pp/marketplace/requests/id-1/results"), any(), any(), eq(Void.class)))
                .thenThrow(new ClientException(503, "unavailable"));

        given().contentType(ContentType.JSON).body(event("id-1", "7", "Solved (Permanently)"))
                .when().post(PATH)
                .then().statusCode(500)
                .body("error", equalTo("InternalException"))
                .body("message", containsString("Unable to deliver result GRANTED for request id-1"));
    }
}
