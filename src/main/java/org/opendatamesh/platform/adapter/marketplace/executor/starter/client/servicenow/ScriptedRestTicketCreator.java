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
public class ScriptedRestTicketCreator implements TicketCreator {

    private static final String DEFAULT_CORRELATION_FIELD = "correlation_id";

    private final ServiceNowClient client;
    private final TicketTemplateRenderer renderer;
    private final ServiceNowConfig config;

    public ScriptedRestTicketCreator(ServiceNowClient client, TicketTemplateRenderer renderer, ServiceNowConfig config) {
        this.client = client;
        this.renderer = renderer;
        this.config = config;
    }

    @Override
    public TicketInterfaceType type() {
        return TicketInterfaceType.SCRIPTED_REST;
    }

    @Override
    public TicketReference create(ServiceNowConfig.TicketProfile profile, Map<String, Object> data, String correlationId) {
        ObjectNode fields = renderFields(profile, data);
        ObjectNode body = buildBody(profile, fields, correlationId);
        JsonNode response = client.send(profile.method(), path(profile), body);
        return toReference(profile, response);
    }

    private ObjectNode renderFields(ServiceNowConfig.TicketProfile profile, Map<String, Object> data) {
        ObjectNode fields = JsonNodeFactory.instance.objectNode();
        profile.fields().forEach((name, template) -> fields.set(name, new TextNode(renderer.render(template, data))));
        return fields;
    }

    private ObjectNode buildBody(ServiceNowConfig.TicketProfile profile, ObjectNode fields, String correlationId) {
        fields.put(profile.correlationField().orElse(DEFAULT_CORRELATION_FIELD), correlationId);
        return fields;
    }

    private TicketReference toReference(ServiceNowConfig.TicketProfile profile, JsonNode response) {
        ServiceNowConfig.ResponseMapping mapping = profile.response();
        String numberPointer = mapping.numberPointer()
                .orElseThrow(() -> new InternalException("Scripted REST profile requires response.number-pointer"));
        String sysIdPointer = mapping.sysIdPointer()
                .orElseThrow(() -> new InternalException("Scripted REST profile requires response.sys-id-pointer"));
        String number = requireText(response, numberPointer, "number");
        String sysId = requireText(response, sysIdPointer, "sys_id");
        String table = mapping.tablePointer().map(p -> response.at(p).asText("")).filter(t -> !t.isBlank())
                .orElse(mapping.table().orElse(""));
        return new TicketReference(number, sysId, table, client.ticketLink(table, sysId));
    }

    private String path(ServiceNowConfig.TicketProfile profile) {
        return profile.path().orElseThrow(() -> new InternalException("Scripted REST profile requires a path"));
    }

    private String requireText(JsonNode response, String pointer, String what) {
        String value = response == null ? "" : response.at(pointer).asText("");
        if (value.isBlank()) {
            throw new InternalException("ServiceNow response did not contain the ticket " + what + " at " + pointer);
        }
        return value;
    }
}
