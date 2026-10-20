package org.opendatamesh.platform.adapter.marketplace.executor.starter.marketplace.services.usecases.completeticket;

import org.opendatamesh.platform.adapter.marketplace.executor.starter.marketplace.domain.MarketplaceOperation;
import org.opendatamesh.platform.adapter.marketplace.executor.starter.marketplace.domain.MarketplaceRequest;
import org.opendatamesh.platform.adapter.marketplace.executor.starter.marketplace.domain.MarketplaceRequestStatus;
import org.opendatamesh.platform.adapter.marketplace.executor.starter.marketplace.domain.MarketplaceResult;
import org.opendatamesh.platform.adapter.marketplace.executor.starter.marketplace.domain.Party;
import org.opendatamesh.platform.adapter.marketplace.executor.starter.marketplace.domain.Provider;
import org.opendatamesh.platform.adapter.marketplace.executor.starter.marketplace.domain.RegisteredAccessRequest;
import org.opendatamesh.platform.adapter.marketplace.executor.starter.marketplace.domain.TicketEvent;
import org.opendatamesh.platform.adapter.marketplace.executor.starter.marketplace.domain.TicketEventIgnoredReason;
import org.opendatamesh.platform.adapter.marketplace.executor.starter.marketplace.domain.TicketOutcome;
import org.opendatamesh.platform.adapter.marketplace.executor.starter.marketplace.domain.TicketOutcomeCause;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

// BDMD-5340: ServiceNow marketplace executor
class CompleteTicketTest {

    private final List<MarketplaceResult> posted = new ArrayList<>();
    private MarketplaceRequestStatus reported;
    private TicketEventIgnoredReason ignored;
    private Optional<RegisteredAccessRequest> registered = Optional.empty();
    private Optional<TicketOutcome> outcome = Optional.empty();

    private final CompleteTicketPresenter presenter = new CompleteTicketPresenter() {
        @Override
        public void presentReported(MarketplaceRequestStatus status) {
            reported = status;
        }

        @Override
        public void presentIgnored(TicketEventIgnoredReason reason) {
            ignored = reason;
        }
    };

    private final CompleteTicketMarketplaceOutboundPort marketplace = new CompleteTicketMarketplaceOutboundPort() {
        @Override
        public Optional<RegisteredAccessRequest> findAccessRequest(String uuid) {
            return registered;
        }

        @Override
        public boolean hasFinalResult(String uuid) {
            return false;
        }

        @Override
        public void sendFinalResult(String uuid, MarketplaceResult result) {
            posted.add(result);
        }
    };

    private RegisteredAccessRequest register(MarketplaceOperation operation, String uuid) {
        MarketplaceRequest request = new MarketplaceRequest("n", uuid, new Provider("urn:dp:finance:sales", List.of("p1", "p2")),
                new Party("user", "a@b.c"), new Party("user", "a@b.c"), Map.of(), new Date(), null);
        RegisteredAccessRequest access = new RegisteredAccessRequest(operation, request);
        registered = Optional.of(access);
        return access;
    }

    private void run(String uuid, Map<String, String> fields) {
        new CompleteTicket(
                new CompleteTicketCommand(new TicketEvent(uuid, fields)),
                presenter,
                marketplace,
                (operation, request) -> true,
                (operation, eventFields) -> outcome,
                (operation, request, found, status, eventFields) ->
                        status + "|" + eventFields.getOrDefault("close_notes", "") + "|" + eventFields.getOrDefault("closed_by", "")
        ).execute();
    }

    /**
     * Scenario: Refused subscribe ticket denies access
     *   Given request "id-1" is a registered MARKETPLACE_SUBSCRIBE without final result
     *   When ServiceNow sends a ticket event for "id-1" matching a REFUSED rule with close_notes "Not authorised"
     *   Then a DENIED result whose message contains "Not authorised" is posted
     */
    @Test
    void whenSubscribeTicketRefusedThenDenied() {
        register(MarketplaceOperation.MARKETPLACE_SUBSCRIBE, "id-1");
        outcome = Optional.of(new TicketOutcome(TicketOutcomeCause.REFUSED, null));

        run("id-1", Map.of("state", "7", "close_notes", "Not authorised"));

        assertEquals(1, posted.size());
        assertEquals(MarketplaceRequestStatus.DENIED, posted.get(0).status());
        assertTrue(posted.get(0).message().contains("Not authorised"));
        assertEquals(MarketplaceRequestStatus.DENIED, reported);
        assertNull(ignored);
    }

    /**
     * Scenario: Refused unsubscribe ticket is reported as a descriptive ERROR
     *   Given request "id-2" is a registered MARKETPLACE_UNSUBSCRIBE without final result
     *   When ServiceNow sends a ticket event for "id-2" matching a REFUSED rule with closed_by "jdoe"
     *   Then an ERROR result whose message states the request was refused on ServiceNow by "jdoe" is posted
     */
    @Test
    void whenUnsubscribeTicketRefusedThenDescriptiveError() {
        register(MarketplaceOperation.MARKETPLACE_UNSUBSCRIBE, "id-2");
        outcome = Optional.of(new TicketOutcome(TicketOutcomeCause.REFUSED, null));

        run("id-2", Map.of("state", "7", "closed_by", "jdoe"));

        assertEquals(1, posted.size());
        assertEquals(MarketplaceRequestStatus.ERROR, posted.get(0).status());
        assertTrue(posted.get(0).message().contains("jdoe"));
        assertEquals(MarketplaceRequestStatus.ERROR, reported);
    }

    /**
     * Scenario: Fulfilled unsubscribe ticket revokes access
     *   Given request "id-2" is a registered MARKETPLACE_UNSUBSCRIBE without final result
     *   When ServiceNow sends a ticket event for "id-2" matching a FULFILLED rule
     *   Then a REVOKED result is posted
     */
    @Test
    void whenUnsubscribeTicketFulfilledThenRevoked() {
        register(MarketplaceOperation.MARKETPLACE_UNSUBSCRIBE, "id-2");
        outcome = Optional.of(new TicketOutcome(TicketOutcomeCause.FULFILLED, null));

        run("id-2", Map.of("state", "7"));

        assertEquals(1, posted.size());
        assertEquals(MarketplaceRequestStatus.REVOKED, posted.get(0).status());
        assertEquals(List.of("p1", "p2"), posted.get(0).provider().dataProductPortsFqn());
        assertEquals(MarketplaceRequestStatus.REVOKED, reported);
    }

    /**
     * Scenario: Non-terminal ticket update is ignored
     *   Given request "id-1" is a registered MARKETPLACE_SUBSCRIBE without final result
     *   When ServiceNow sends a ticket event for "id-1" with state "2"
     *   Then no result is posted and the reason is "NON_TERMINAL_STATE"
     */
    @Test
    void whenNoRuleMatchesThenIgnoredAsNonTerminal() {
        register(MarketplaceOperation.MARKETPLACE_SUBSCRIBE, "id-1");
        outcome = Optional.empty();

        run("id-1", Map.of("state", "2"));

        assertTrue(posted.isEmpty());
        assertEquals(TicketEventIgnoredReason.NON_TERMINAL_STATE, ignored);
        assertNull(reported);
    }

    /**
     * Scenario: Unknown correlation is ignored
     *   When ServiceNow sends a ticket event for an identifier unknown to the Marketplace Service
     *   Then no result is posted and the reason is "UNKNOWN_REQUEST"
     */
    @Test
    void whenRequestUnknownThenIgnored() {
        registered = Optional.empty();

        run("unknown", Map.of("state", "7"));

        assertTrue(posted.isEmpty());
        assertEquals(TicketEventIgnoredReason.UNKNOWN_REQUEST, ignored);
    }
}
