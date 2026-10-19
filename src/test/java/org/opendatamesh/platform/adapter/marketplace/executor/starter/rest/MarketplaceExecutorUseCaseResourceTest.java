package org.opendatamesh.platform.adapter.marketplace.executor.starter.rest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.opendatamesh.platform.adapter.marketplace.executor.starter.config.ServiceNowApi;
import org.opendatamesh.platform.adapter.marketplace.executor.starter.marketplace.domain.MarketplaceRequestStatus;
import org.opendatamesh.platform.adapter.marketplace.executor.starter.resources.MarketplaceResponseRes;
import org.opendatamesh.platform.adapter.marketplace.executor.starter.utils.client.RestUtils;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.when;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.endsWith;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

// BDMD-5340: ServiceNow marketplace executor
@QuarkusTest
class MarketplaceExecutorUseCaseResourceTest {

    private static final String PATH = "/api/v1/up/marketplace-executor/requests";

    @InjectMock
    RestUtils restUtils;

    @InjectMock
    @ServiceNowApi
    RestUtils serviceNowRestUtils;

    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * Scenario: Subscribe request opens a Table API ticket and reports PENDING
     *   Given the subscribe profile uses the Table API on table "incident"
     *   And ServiceNow answers with ticket number "INC0010001"
     *   When the Marketplace Service submits a MARKETPLACE_SUBSCRIBE request "id-1" for data product "urn:dp:finance:sales"
     *   Then the executor answers 202
     *   And an incident is created whose "correlation_id" is "id-1"
     *   And a PENDING result mentioning "INC0010001" is posted for request "id-1"
     */
    @Test
    void whenSubscribeRequestThenTableTicketOpenedAndPendingReported() throws Exception {
        when(serviceNowRestUtils.genericPost(contains("/api/now/table/incident"), any(), any(), eq(JsonNode.class)))
                .thenReturn(mapper.readTree("{\"result\":{\"number\":\"INC0010001\",\"sys_id\":\"abc\"}}"));

        given().contentType(ContentType.JSON).body(body("MARKETPLACE_SUBSCRIBE", "id-1", "2025-01-01T00:00:00.000+0000", "2025-12-31T00:00:00.000+0000"))
                .when().post(PATH)
                .then().statusCode(202);

        ArgumentCaptor<Object> ticket = ArgumentCaptor.forClass(Object.class);
        verify(serviceNowRestUtils, timeout(5000)).genericPost(contains("/api/now/table/incident"), any(), ticket.capture(), eq(JsonNode.class));
        assertEquals("id-1", ((JsonNode) ticket.getValue()).get("correlation_id").asText());
        MarketplaceResponseRes pending = capturePendingResult("id-1");
        assertEquals(MarketplaceRequestStatus.PENDING, pending.getStatus());
        assertTrue(pending.getMessage().contains("INC0010001"));
    }

    /**
     * Scenario: Unsubscribe request opens an Import Set ticket
     *   Given the unsubscribe profile uses the Import Set API on staging table "u_marketplace_revoke"
     *   When the Marketplace Service submits a MARKETPLACE_UNSUBSCRIBE request "id-2"
     *   Then a row is posted to "/api/now/import/u_marketplace_revoke" whose "u_marketplace_request_id" is "id-2"
     *   And a PENDING result is posted for request "id-2"
     */
    @Test
    void whenUnsubscribeRequestThenImportSetRowPostedAndPendingReported() throws Exception {
        when(serviceNowRestUtils.genericPost(contains("/api/now/import/u_marketplace_revoke"), any(), any(), eq(JsonNode.class)))
                .thenReturn(mapper.readTree("{\"result\":[{\"status\":\"inserted\",\"sys_id\":\"s-1\",\"display_value\":\"TASK0001\",\"table\":\"sc_task\"}]}"));

        given().contentType(ContentType.JSON).body(body("MARKETPLACE_UNSUBSCRIBE", "id-2", "2025-01-01T00:00:00.000+0000", "2025-12-31T00:00:00.000+0000"))
                .when().post(PATH)
                .then().statusCode(202);

        ArgumentCaptor<Object> row = ArgumentCaptor.forClass(Object.class);
        verify(serviceNowRestUtils, timeout(5000)).genericPost(contains("/api/now/import/u_marketplace_revoke"), any(), row.capture(), eq(JsonNode.class));
        assertEquals("id-2", ((JsonNode) row.getValue()).get("u_marketplace_request_id").asText());
        assertEquals(MarketplaceRequestStatus.PENDING, capturePendingResult("id-2").getStatus());
    }

    /**
     * Scenario: Request outside the filter is declined without side effects
     *   Given the filter accepts only data products matching the regex "urn:dp:finance:.*"
     *   When the Marketplace Service submits a request for data product "urn:dp:hr:payroll"
     *   Then the executor answers 409 with error "RequestNotHandledException"
     *   And no ServiceNow call and no result post happen
     */
    @Test
    void whenRequestOutsideFilterThenConflictAndNoSideEffects() {
        given().contentType(ContentType.JSON)
                .body(body("MARKETPLACE_SUBSCRIBE", "id-1", "2025-01-01T00:00:00.000+0000", "2025-12-31T00:00:00.000+0000")
                        .replace("urn:dp:finance:sales", "urn:dp:hr:payroll"))
                .when().post(PATH)
                .then().statusCode(409)
                .body("error", equalTo("RequestNotHandledException"));

        verifyNoInteractions(restUtils, serviceNowRestUtils);
    }

    private MarketplaceResponseRes capturePendingResult(String uuid) {
        ArgumentCaptor<Object> result = ArgumentCaptor.forClass(Object.class);
        verify(restUtils, timeout(5000)).genericPost(endsWith("/api/v1/pp/marketplace/requests/" + uuid + "/results"), any(), result.capture(), eq(Void.class));
        return (MarketplaceResponseRes) result.getValue();
    }

    @Test
    void unknownOperationIsRejected() {
        given().contentType(ContentType.JSON).body(body("MARKETPLACE_FOO", "id-1", "2025-01-01T00:00:00.000+0000", "2025-12-31T00:00:00.000+0000"))
                .when().post(PATH)
                .then().statusCode(400)
                .body("error", equalTo("BadRequestException"))
                .body("message", containsString("Invalid operation type"));

        verifyNoInteractions(restUtils);
    }

    @Test
    void startAfterEndIsRejected() {
        given().contentType(ContentType.JSON).body(body("MARKETPLACE_SUBSCRIBE", "id-1", "2026-01-01T00:00:00.000+0000", "2025-01-01T00:00:00.000+0000"))
                .when().post(PATH)
                .then().statusCode(400)
                .body("message", equalTo("Start date must be before end date"));

        verifyNoInteractions(restUtils);
    }

    @Test
    void missingFieldsAreRejectedByValidation() {
        given().contentType(ContentType.JSON).body("{\"v\":\"1\"}")
                .when().post(PATH)
                .then().statusCode(400)
                .body("error", equalTo("BadRequest"))
                .body("path", equalTo(PATH));
    }

    private String body(String operation, String id, String start, String end) {
        return """
                {"v":"1","operation":"%s","request":{"name":"n","identifier":"%s",
                "provider":{"dataProductFqn":"urn:dp:finance:sales","dataProductPortsFqn":["p"]},
                "consumer":{"type":"user","identifier":"a@b.c"},
                "requester":{"type":"user","identifier":"a@b.c"},
                "startDate":"%s","endDate":"%s"}}
                """.formatted(operation, id, start, end);
    }
}
