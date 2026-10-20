package org.opendatamesh.platform.adapter.marketplace.executor.starter.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import org.opendatamesh.platform.adapter.marketplace.executor.starter.utils.client.RestUtils;
import org.opendatamesh.platform.adapter.marketplace.executor.starter.utils.client.RestUtilsFactory;
import org.opendatamesh.platform.adapter.marketplace.executor.starter.utils.client.http.HttpHeader;
import org.opendatamesh.platform.adapter.marketplace.executor.starter.utils.client.http.Oauth2;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;

public class ServiceNowRestUtilsProducer {

    @Produces
    @ApplicationScoped
    @ServiceNowApi
    RestUtils serviceNowRestUtils(ServiceNowConfig config, ObjectMapper objectMapper) {
        return RestUtilsFactory.getAuthenticatedRestUtils(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(60)).build(),
                objectMapper,
                connectionHeaders(config),
                oauth2(config)
        );
    }

    private List<HttpHeader> connectionHeaders(ServiceNowConfig config) {
        if (config.auth().type() != ServiceNowAuthType.API_KEY) {
            return List.of();
        }
        ServiceNowConfig.ApiKey apiKey = config.auth().apiKey();
        return List.of(new HttpHeader(apiKey.headerName(), apiKey.value().orElseThrow()));
    }

    private Oauth2 oauth2(ServiceNowConfig config) {
        if (config.auth().type() != ServiceNowAuthType.OAUTH2_CLIENT_CREDENTIALS) {
            return null;
        }
        ServiceNowConfig.OAuth2 settings = config.auth().oauth2();
        Oauth2 oauth2 = new Oauth2();
        oauth2.setUrl(settings.tokenUrl().orElse(stripTrailingSlash(config.instanceUrl()) + "/oauth_token.do"));
        oauth2.setGrantType("client_credentials");
        oauth2.setScope(settings.scope().orElse(null));
        oauth2.setClientId(settings.clientId().orElse(null));
        oauth2.setClientSecret(settings.clientSecret().orElse(null));
        return oauth2;
    }

    private String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
