package org.opendatamesh.platform.adapter.marketplace.executor.starter.client.servicenow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import jakarta.enterprise.context.ApplicationScoped;
import org.opendatamesh.platform.adapter.marketplace.executor.starter.config.ServiceNowConfig;
import org.opendatamesh.platform.adapter.marketplace.executor.starter.config.TicketInterfaceType;
import org.opendatamesh.platform.adapter.marketplace.executor.starter.exceptions.InternalException;
import org.opendatamesh.platform.adapter.marketplace.executor.starter.marketplace.domain.TicketReference;

import java.util.Map;

@ApplicationScoped
public class ImportSetTicketCreator implements TicketCreator {

    private static final String DEFAULT_CORRELATION_COLUMN = "u_marketplace_request_id";

    private final ServiceNowClient client;
    private final TicketTemplateRenderer renderer;
    private final ServiceNowConfig config;

    public ImportSetTicketCreator(ServiceNowClient client, TicketTemplateRenderer renderer, ServiceNowConfig config) {
        this.client = client;
        this.renderer = renderer;
        this.config = config;
    }

    @Override
    public TicketInterfaceType type() {
        return TicketInterfaceType.IMPORT_SET;
    }

    @Override
    public TicketReference create(ServiceNowConfig.TicketProfile profile, Map<String, Object> data, String correlationId) {
        ObjectNode fields = renderFields(profile, data);
        ObjectNode body = buildBody(profile, fields, correlationId);
        JsonNode response = client.send("POST", "/api/now/import/" + target(profile), body);
        ensureRowTransformed(response);
        return toReference(profile, response);
    }

    private ObjectNode renderFields(ServiceNowConfig.TicketProfile profile, Map<String, Object> data) {
        ObjectNode fields = JsonNodeFactory.instance.objectNode();
        profile.fields().forEach((name, template) -> fields.set(name, new TextNode(renderer.render(template, data))));
        return fields;
    }

    private ObjectNode buildBody(ServiceNowConfig.TicketProfile profile, ObjectNode fields, String correlationId) {
        fields.put(profile.correlationField().orElse(DEFAULT_CORRELATION_COLUMN), correlationId);
        return fields;
    }

    private void ensureRowTransformed(JsonNode response) {
        String status = response == null ? "" : response.at("/result/0/status").asText("");
        if ("error".equalsIgnoreCase(status) || "ignored".equalsIgnoreCase(status)) {
            String detail = firstNonBlank(response.at("/result/0/error_message").asText(""),
                    response.at("/result/0/status_message").asText(""));
            throw new InternalException("Import Set transform did not create the ticket (status " + status + "): " + detail);
        }
    }

    private TicketReference toReference(ServiceNowConfig.TicketProfile profile, JsonNode response) {
        ServiceNowConfig.ResponseMapping mapping = profile.response();
        String numberPointer = mapping.numberPointer().orElse("/result/0/display_value");
        String sysIdPointer = mapping.sysIdPointer().orElse("/result/0/sys_id");
        String number = requireText(response, numberPointer, "number");
        String sysId = requireText(response, sysIdPointer, "sys_id");
        String table = mapping.table().orElseGet(() -> response.at(mapping.tablePointer().orElse("/result/0/table")).asText(""));
        return new TicketReference(number, sysId, table, client.ticketLink(table, sysId));
    }

    private String target(ServiceNowConfig.TicketProfile profile) {
        return profile.target().orElseThrow(() -> new InternalException("Import Set profile requires a staging table target"));
    }

    private String firstNonBlank(String first, String second) {
        return first.isBlank() ? second : first;
    }

    private String requireText(JsonNode response, String pointer, String what) {
        String value = response == null ? "" : response.at(pointer).asText("");
        if (value.isBlank()) {
            throw new InternalException("ServiceNow response did not contain the ticket " + what + " at " + pointer);
        }
        return value;
    }
}
