package org.opendatamesh.platform.adapter.marketplace.executor.starter.config;

import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import org.opendatamesh.platform.adapter.marketplace.executor.starter.marketplace.domain.MarketplaceOperation;
import org.opendatamesh.platform.adapter.marketplace.executor.starter.marketplace.domain.TicketOutcomeCause;
import org.opendatamesh.platform.adapter.marketplace.executor.starter.marketplace.services.OutcomeRulesService;
import org.opendatamesh.platform.adapter.marketplace.executor.starter.marketplace.services.RequestFilterService;
import org.opendatamesh.platform.adapter.marketplace.executor.starter.client.servicenow.TicketTemplateRenderer;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@ApplicationScoped
public class ServiceNowConfigValidator {

    private static final Set<String> ALLOWED_METHODS = Set.of("POST", "PATCH");

    private final ServiceNowConfig config;
    private final RequestFilterService filterService;
    private final OutcomeRulesService outcomeRulesService;
    private final TicketTemplateRenderer renderer;

    public ServiceNowConfigValidator(ServiceNowConfig config, RequestFilterService filterService,
                                     OutcomeRulesService outcomeRulesService, TicketTemplateRenderer renderer) {
        this.config = config;
        this.filterService = filterService;
        this.outcomeRulesService = outcomeRulesService;
        this.renderer = renderer;
    }

    void onStart(@Observes StartupEvent event) {
        validate();
    }

    public void validate() {
        List<String> problems = new ArrayList<>();
        verifyConnection(problems);
        for (MarketplaceOperation operation : MarketplaceOperation.values()) {
            ServiceNowConfig.TicketProfile profile = config.profiles().forOperation(operation);
            verifyInterfaceSettings(problems, operation, profile);
            verifyFields(problems, operation, profile);
            verifyRules(problems, operation, profile);
        }
        verifyRulePatterns(problems);
        verifyFilter(problems);
        verifyTemplates(problems);
        failIfAny(problems);
    }

    private void verifyConnection(List<String> problems) {
        verifyInstanceUrl(problems);
        ServiceNowConfig.Auth auth = config.auth();
        if (auth.type() == ServiceNowAuthType.OAUTH2_CLIENT_CREDENTIALS) {
            requireText(problems, auth.oauth2().clientId(), "servicenow.auth.oauth2.client-id is required for OAUTH2_CLIENT_CREDENTIALS");
            requireText(problems, auth.oauth2().clientSecret(), "servicenow.auth.oauth2.client-secret is required for OAUTH2_CLIENT_CREDENTIALS");
        } else if (auth.type() == ServiceNowAuthType.API_KEY) {
            requireText(problems, auth.apiKey().value(), "servicenow.auth.api-key.value is required for API_KEY");
        }
    }

    private void verifyInstanceUrl(List<String> problems) {
        String url = config.instanceUrl();
        if (isBlank(url)) {
            problems.add("servicenow.instance-url is required");
            return;
        }
        if (!isValidInstanceUrl(url)) {
            problems.add("servicenow.instance-url must be a valid https URL (http is allowed only for http://localhost)");
        }
    }

    private boolean isValidInstanceUrl(String url) {
        try {
            URI uri = URI.create(url);
            boolean secure = "https".equalsIgnoreCase(uri.getScheme());
            boolean localhost = url.startsWith("http://localhost");
            return (secure || localhost) && uri.getHost() != null;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private void verifyInterfaceSettings(List<String> problems, MarketplaceOperation operation, ServiceNowConfig.TicketProfile profile) {
        String name = profileName(operation);
        switch (profile.type()) {
            case TABLE, SERVICE_CATALOG, IMPORT_SET ->
                    requireText(problems, profile.target(), name + ": target is required for " + profile.type());
            case SCRIPTED_REST -> {
                requireText(problems, profile.path(), name + ": path is required for SCRIPTED_REST");
                requireText(problems, profile.response().numberPointer(), name + ": response.number-pointer is required for SCRIPTED_REST");
                requireText(problems, profile.response().sysIdPointer(), name + ": response.sys-id-pointer is required for SCRIPTED_REST");
            }
        }
        if (!ALLOWED_METHODS.contains(String.valueOf(profile.method()))) {
            problems.add(name + ": method must be one of " + ALLOWED_METHODS + " but was " + profile.method());
        }
    }

    private void verifyFields(List<String> problems, MarketplaceOperation operation, ServiceNowConfig.TicketProfile profile) {
        String correlationTarget = effectiveCorrelationTarget(profile);
        if (profile.fields().containsKey(correlationTarget)) {
            problems.add(profileName(operation) + ": field '" + correlationTarget
                    + "' overrides the correlation field and must be removed from fields");
        }
    }

    private String effectiveCorrelationTarget(ServiceNowConfig.TicketProfile profile) {
        return profile.correlationField().orElse(switch (profile.type()) {
            case TABLE, SCRIPTED_REST -> "correlation_id";
            case SERVICE_CATALOG -> "marketplace_request_id";
            case IMPORT_SET -> "u_marketplace_request_id";
        });
    }

    private void verifyRules(List<String> problems, MarketplaceOperation operation, ServiceNowConfig.TicketProfile profile) {
        List<ServiceNowConfig.OutcomeRule> rules = profile.outcomeRules();
        String name = profileName(operation);
        if (rules.isEmpty()) {
            problems.add(name + ": at least one outcome rule is required");
            return;
        }
        if (rules.stream().noneMatch(rule -> rule.outcome() == TicketOutcomeCause.FULFILLED)) {
            problems.add(name + ": at least one outcome rule must have outcome FULFILLED");
        }
        for (int i = 0; i < rules.size(); i++) {
            if (rules.get(i).when().isEmpty()) {
                problems.add(name + ": outcome rule " + i + " must declare a non-empty 'when'");
            }
        }
    }

    private void verifyRulePatterns(List<String> problems) {
        collect(problems, outcomeRulesService::verifyRules);
    }

    private void verifyFilter(List<String> problems) {
        collect(problems, filterService::verifyPatterns);
    }

    private void verifyTemplates(List<String> problems) {
        for (MarketplaceOperation operation : MarketplaceOperation.values()) {
            ServiceNowConfig.TicketProfile profile = config.profiles().forOperation(operation);
            profile.fields().forEach((field, template) -> verifyTemplate(problems, profileName(operation) + " field '" + field + "'", template));
            profile.outcomeRules().forEach(rule -> rule.message()
                    .ifPresent(template -> verifyTemplate(problems, profileName(operation) + " outcome rule message", template)));
            verifyProfileMessages(problems, operation, profile.messages());
        }
        verifyGlobalMessages(problems, config.messages());
    }

    private void verifyProfileMessages(List<String> problems, MarketplaceOperation operation, ServiceNowConfig.ProfileMessages messages) {
        String name = profileName(operation) + " message ";
        verifyOptionalTemplate(problems, name + "pending", messages.pending());
        verifyOptionalTemplate(problems, name + "fulfilled", messages.fulfilled());
        verifyOptionalTemplate(problems, name + "refused", messages.refused());
        verifyOptionalTemplate(problems, name + "failed", messages.failed());
        verifyOptionalTemplate(problems, name + "technical-error", messages.technicalError());
    }

    private void verifyGlobalMessages(List<String> problems, ServiceNowConfig.Messages messages) {
        verifyTemplate(problems, "global message pending", messages.pending());
        verifyTemplate(problems, "global message fulfilled", messages.fulfilled());
        verifyTemplate(problems, "global message refused", messages.refused());
        verifyTemplate(problems, "global message failed", messages.failed());
        verifyTemplate(problems, "global message technical-error", messages.technicalError());
    }

    private void verifyOptionalTemplate(List<String> problems, String name, Optional<String> template) {
        template.ifPresent(value -> verifyTemplate(problems, name, value));
    }

    private void verifyTemplate(List<String> problems, String name, String template) {
        if (template == null) {
            return;
        }
        try {
            renderer.verify(template);
        } catch (RuntimeException e) {
            problems.add(name + ": " + e.getMessage());
        }
    }

    private void collect(List<String> problems, Runnable verification) {
        try {
            verification.run();
        } catch (RuntimeException e) {
            problems.add(e.getMessage());
        }
    }

    private void requireText(List<String> problems, Optional<String> value, String message) {
        if (value.isEmpty() || value.get().isBlank()) {
            problems.add(message);
        }
    }

    private void failIfAny(List<String> problems) {
        if (problems.isEmpty()) {
            return;
        }
        StringBuilder message = new StringBuilder("Invalid ServiceNow executor configuration:");
        for (int i = 0; i < problems.size(); i++) {
            message.append("\n  ").append(i + 1).append(". ").append(problems.get(i));
        }
        throw new IllegalStateException(message.toString());
    }

    private String profileName(MarketplaceOperation operation) {
        return "profile " + (operation == MarketplaceOperation.MARKETPLACE_SUBSCRIBE ? "subscribe" : "unsubscribe");
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
