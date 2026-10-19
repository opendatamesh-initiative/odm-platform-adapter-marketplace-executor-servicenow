package org.opendatamesh.platform.adapter.marketplace.executor.starter.servicenow.services.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.enterprise.context.ApplicationScoped;
import org.opendatamesh.platform.adapter.marketplace.executor.starter.config.ServiceNowApi;
import org.opendatamesh.platform.adapter.marketplace.executor.starter.config.ServiceNowConfig;
import org.opendatamesh.platform.adapter.marketplace.executor.starter.exceptions.InternalException;
import org.opendatamesh.platform.adapter.marketplace.executor.starter.exceptions.client.ClientException;
import org.opendatamesh.platform.adapter.marketplace.executor.starter.exceptions.client.ClientResourceMappingException;
import org.opendatamesh.platform.adapter.marketplace.executor.starter.utils.client.RestUtils;
import org.opendatamesh.platform.adapter.marketplace.executor.starter.utils.client.http.HttpHeader;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Collectors;

@ApplicationScoped
public class ServiceNowClient {

    private static final int MAX_BODY_LENGTH = 500;

    private final RestUtils restUtils;
    private final ServiceNowConfig config;

    public ServiceNowClient(@ServiceNowApi RestUtils restUtils, ServiceNowConfig config) {
        this.restUtils = restUtils;
        this.config = config;
    }

    public JsonNode send(String method, String path, ObjectNode body) {
        String url = baseUrl() + path;
        return translateErrors(method, path, () -> switch (method) {
            case "POST" -> restUtils.genericPost(url, headers(), body, JsonNode.class);
            case "PATCH" -> restUtils.genericPatch(url, headers(), body, JsonNode.class);
            default -> throw new InternalException("Unsupported ServiceNow HTTP method " + method + ": only POST and PATCH are supported");
        });
    }

    public JsonNode get(String path, Map<String, String> queryParams) {
        String url = baseUrl() + path + queryString(queryParams);
        return translateErrors("GET", path, () -> restUtils.genericGet(url, headers(), null, JsonNode.class));
    }

    public String ticketLink(String table, String sysId) {
        if (isBlank(table) || isBlank(sysId)) {
            return "";
        }
        return baseUrl() + "/" + table + ".do?sys_id=" + sysId;
    }

    private JsonNode translateErrors(String method, String path, Supplier<JsonNode> call) {
        try {
            return call.get();
        } catch (ClientException e) {
            throw clientError(e, method, path);
        } catch (ClientResourceMappingException e) {
            throw new InternalException("Unexpected ServiceNow response for " + path, e);
        } catch (IllegalStateException e) {
            throw new InternalException("Unable to obtain a ServiceNow OAuth token: " + e.getMessage()
                    + ". Check client credentials and that inbound client credentials are enabled on the instance", e);
        }
    }

    private InternalException clientError(ClientException e, String method, String path) {
        if (e.getCode() == 401 || e.getCode() == 403) {
            return new InternalException("ServiceNow rejected the integration credentials (HTTP " + e.getCode()
                    + ", auth type " + config.auth().type() + "): check the OAuth application / API key, "
                    + "the integration user roles and the API access policy for " + path);
        }
        return new InternalException("ServiceNow call " + method + " " + path + " failed with HTTP " + e.getCode()
                + ": " + truncate(e.getResponseBody()));
    }

    private List<HttpHeader> headers() {
        return List.of(
                new HttpHeader("Content-Type", "application/json"),
                new HttpHeader("Accept", "application/json")
        );
    }

    private String queryString(Map<String, String> queryParams) {
        if (queryParams == null || queryParams.isEmpty()) {
            return "";
        }
        return "?" + queryParams.entrySet().stream()
                .map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "="
                        + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
    }

    private String baseUrl() {
        String url = config.instanceUrl();
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private String truncate(String value) {
        if (value == null) {
            return "";
        }
        return value.length() <= MAX_BODY_LENGTH ? value : value.substring(0, MAX_BODY_LENGTH);
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
