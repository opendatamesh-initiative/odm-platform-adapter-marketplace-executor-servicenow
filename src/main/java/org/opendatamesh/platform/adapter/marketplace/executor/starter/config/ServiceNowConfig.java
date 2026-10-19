package org.opendatamesh.platform.adapter.marketplace.executor.starter.config;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import org.opendatamesh.platform.adapter.marketplace.executor.starter.marketplace.domain.MarketplaceOperation;
import org.opendatamesh.platform.adapter.marketplace.executor.starter.marketplace.domain.TicketOutcomeCause;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@ConfigMapping(prefix = "servicenow")
public interface ServiceNowConfig {

    String instanceUrl();

    Auth auth();

    Filter filter();

    Profiles profiles();

    Messages messages();

    interface Auth {

        @WithDefault("OAUTH2_CLIENT_CREDENTIALS")
        ServiceNowAuthType type();

        OAuth2 oauth2();

        ApiKey apiKey();
    }

    interface OAuth2 {

        Optional<String> clientId();

        Optional<String> clientSecret();

        /** Defaults to {@code instanceUrl + "/oauth_token.do"} when absent. */
        Optional<String> tokenUrl();

        Optional<String> scope();
    }

    interface ApiKey {

        @WithDefault("x-sn-apikey")
        String headerName();

        Optional<String> value();
    }

    /** Every criterion is a list of full-match regular expressions; an absent criterion matches everything. */
    interface Filter {

        Optional<List<String>> operations();

        Optional<List<String>> providerDataProductFqns();

        Optional<List<String>> portFqns();

        Optional<List<String>> consumerTypes();

        Optional<List<String>> consumerIdentifiers();

        Optional<List<String>> requesterIdentifiers();

        /** Request property key to regular expression. */
        Map<String, String> properties();
    }

    interface Profiles {

        TicketProfile subscribe();

        TicketProfile unsubscribe();

        default TicketProfile forOperation(MarketplaceOperation operation) {
            return switch (operation) {
                case MARKETPLACE_SUBSCRIBE -> subscribe();
                case MARKETPLACE_UNSUBSCRIBE -> unsubscribe();
            };
        }
    }

    interface TicketProfile {

        TicketInterfaceType type();

        Optional<String> target();

        Optional<String> path();

        @WithDefault("POST")
        String method();

        Optional<String> correlationField();

        Map<String, String> fields();

        /** SERVICE_CATALOG only: the sys_user field compared with the requester identifier. */
        @WithDefault("email")
        String requesterMatchField();

        @WithDefault("true")
        boolean submitCartWhenTwoStep();

        ResponseMapping response();

        List<OutcomeRule> outcomeRules();

        ProfileMessages messages();
    }

    interface ResponseMapping {

        Optional<String> numberPointer();

        Optional<String> sysIdPointer();

        Optional<String> tablePointer();

        Optional<String> table();
    }

    interface OutcomeRule {

        /** Ticket field to full-match regular expression. */
        Map<String, String> when();

        TicketOutcomeCause outcome();

        Optional<String> message();
    }

    interface ProfileMessages {

        Optional<String> pending();

        Optional<String> fulfilled();

        Optional<String> refused();

        Optional<String> failed();

        Optional<String> technicalError();
    }

    interface Messages {

        @WithDefault("ServiceNow ticket {ticket.number} opened for {operation} of {request.provider.dataProductFqn} (ports: {#each request.provider.dataProductPortsFqn}{it}{#if it_hasNext}, {/if}{/each}). Awaiting fulfilment: {ticket.link}")
        String pending();

        @WithDefault("{outcome.status} through ServiceNow ticket {ticket.number} ({ticket.link}){#if ticket.closedBy} closed by {ticket.closedBy}{/if}.{#if fields.close_notes} Close notes: {fields.close_notes}{/if}")
        String fulfilled();

        @WithDefault("Request refused on ServiceNow: ticket {ticket.number} ({ticket.link}){#if ticket.closedBy} closed by {ticket.closedBy}{/if}.{#if fields.close_notes} Reason: {fields.close_notes}{/if}")
        String refused();

        @WithDefault("ServiceNow reported a failure on ticket {fields.number} ({ticket.link}).{#if fields.close_notes} Details: {fields.close_notes}{/if}")
        String failed();

        @WithDefault("Technical error while processing {operation} on ServiceNow: {error.message}")
        String technicalError();

        ClosingUser closingUser();
    }

    interface ClosingUser {

        @WithDefault("true")
        boolean include();

        @WithDefault("closed_by")
        String field();
    }
}
