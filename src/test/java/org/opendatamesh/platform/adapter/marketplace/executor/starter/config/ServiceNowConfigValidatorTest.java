package org.opendatamesh.platform.adapter.marketplace.executor.starter.config;

import org.junit.jupiter.api.Test;
import org.opendatamesh.platform.adapter.marketplace.executor.starter.marketplace.domain.MarketplaceOperation;
import org.opendatamesh.platform.adapter.marketplace.executor.starter.marketplace.domain.TicketOutcomeCause;
import org.opendatamesh.platform.adapter.marketplace.executor.starter.marketplace.services.OutcomeRulesService;
import org.opendatamesh.platform.adapter.marketplace.executor.starter.marketplace.services.RequestFilterService;
import org.opendatamesh.platform.adapter.marketplace.executor.starter.client.servicenow.TicketTemplateRenderer;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Answers.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

// BDMD-5340: ServiceNow marketplace executor
class ServiceNowConfigValidatorTest {

    /**
     * Scenario: Invalid configuration prevents startup
     *     Given a profile whose field map contains the correlation field
     *     When the configuration is validated
     *     Then validation fails naming the offending profile and field
     */
    @Test
    void whenFieldOverridesCorrelationThenValidationFails() {
        ServiceNowConfigValidator validator = validatorWithSubscribeFields(Map.of("correlation_id", "forged", "short_description", "x"));

        IllegalStateException e = assertThrows(IllegalStateException.class, validator::validate);

        assertTrue(e.getMessage().contains("1. profile subscribe: field 'correlation_id' overrides the correlation field"), e.getMessage());
        assertFalse(e.getMessage().contains("secret-key"));
    }

    @Test
    void whenConfigurationIsValidThenValidationPasses() {
        assertDoesNotThrow(validatorWithSubscribeFields(Map.of("short_description", "x"))::validate);
    }

    private ServiceNowConfigValidator validatorWithSubscribeFields(Map<String, String> subscribeFields) {
        ServiceNowConfig config = mock(ServiceNowConfig.class, RETURNS_DEEP_STUBS);
        when(config.instanceUrl()).thenReturn("https://acme.service-now.com");
        when(config.auth().type()).thenReturn(ServiceNowAuthType.API_KEY);
        when(config.auth().apiKey().value()).thenReturn(Optional.of("secret-key"));
        ServiceNowConfig.TicketProfile subscribe = profile(TicketInterfaceType.TABLE, subscribeFields);
        ServiceNowConfig.TicketProfile unsubscribe = profile(TicketInterfaceType.IMPORT_SET, Map.of());
        when(config.profiles().forOperation(MarketplaceOperation.MARKETPLACE_SUBSCRIBE)).thenReturn(subscribe);
        when(config.profiles().forOperation(MarketplaceOperation.MARKETPLACE_UNSUBSCRIBE)).thenReturn(unsubscribe);
        return new ServiceNowConfigValidator(config, mock(RequestFilterService.class), mock(OutcomeRulesService.class), mock(TicketTemplateRenderer.class));
    }

    private ServiceNowConfig.TicketProfile profile(TicketInterfaceType type, Map<String, String> fields) {
        List<ServiceNowConfig.OutcomeRule> rules = List.of(fulfilledRule());
        ServiceNowConfig.TicketProfile profile = mock(ServiceNowConfig.TicketProfile.class, RETURNS_DEEP_STUBS);
        when(profile.type()).thenReturn(type);
        when(profile.target()).thenReturn(Optional.of("incident"));
        when(profile.method()).thenReturn("POST");
        when(profile.correlationField()).thenReturn(Optional.empty());
        when(profile.fields()).thenReturn(fields);
        when(profile.outcomeRules()).thenReturn(rules);
        return profile;
    }

    private ServiceNowConfig.OutcomeRule fulfilledRule() {
        ServiceNowConfig.OutcomeRule rule = mock(ServiceNowConfig.OutcomeRule.class);
        when(rule.when()).thenReturn(Map.of("state", "6"));
        when(rule.outcome()).thenReturn(TicketOutcomeCause.FULFILLED);
        when(rule.message()).thenReturn(Optional.empty());
        return rule;
    }
}
