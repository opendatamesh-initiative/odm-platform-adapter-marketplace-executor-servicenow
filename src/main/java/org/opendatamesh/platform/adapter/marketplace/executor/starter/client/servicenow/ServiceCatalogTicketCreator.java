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
public class ServiceCatalogTicketCreator implements TicketCreator {

    private static final String DEFAULT_CORRELATION_VARIABLE = "marketplace_request_id";
    private static final String SUBMIT_ORDER_PATH = "/api/sn_sc/servicecatalog/cart/submit_order";

    private final ServiceNowClient client;
    private final TicketTemplateRenderer renderer;
    private final ServiceNowConfig config;

    public ServiceCatalogTicketCreator(ServiceNowClient client, TicketTemplateRenderer renderer, ServiceNowConfig config) {
        this.client = client;
        this.renderer = renderer;
        this.config = config;
    }

    @Override
    public TicketInterfaceType type() {
        return TicketInterfaceType.SERVICE_CATALOG;
    }

    @Override
    public TicketReference create(ServiceNowConfig.TicketProfile profile, Map<String, Object> data, String correlationId) {
        String requesterSysId = resolveRequester(profile, data);
        ObjectNode fields = renderFields(profile, data);
        ObjectNode body = buildBody(profile, fields, requesterSysId, correlationId);
        JsonNode response = client.send("POST", "/api/sn_sc/servicecatalog/items/" + target(profile) + "/order_now", body);
        response = submitCartWhenTwoStep(profile, response);
        return toReference(profile, response);
    }

    private String resolveRequester(ServiceNowConfig.TicketProfile profile, Map<String, Object> data) {
        Map<?, ?> requester = (Map<?, ?>) ((Map<?, ?>) data.get("request")).get("requester");
        String type = String.valueOf(requester.get("type"));
        String identifier = String.valueOf(requester.get("identifier"));
        if (!"user".equalsIgnoreCase(type)) {
            throw new InternalException("Catalog orders require a user requester; got " + type);
        }
        if (identifier.contains("^")) {
            throw new InternalException("Requester identifier contains the unsupported character '^'");
        }
        JsonNode result = client.get("/api/now/table/sys_user", Map.of(
                "sysparm_query", profile.requesterMatchField() + "=" + identifier,
                "sysparm_fields", "sys_id",
                "sysparm_limit", "1"));
        String sysId = result == null ? "" : result.at("/result/0/sys_id").asText("");
        if (sysId.isBlank()) {
            throw new InternalException("Requester " + identifier + " not found in ServiceNow sys_user ("
                    + profile.requesterMatchField() + ")");
        }
        return sysId;
    }

    private ObjectNode renderFields(ServiceNowConfig.TicketProfile profile, Map<String, Object> data) {
        ObjectNode fields = JsonNodeFactory.instance.objectNode();
        profile.fields().forEach((name, template) -> fields.set(name, new TextNode(renderer.render(template, data))));
        return fields;
    }

    private ObjectNode buildBody(ServiceNowConfig.TicketProfile profile, ObjectNode fields, String requesterSysId, String correlationId) {
        fields.put(profile.correlationField().orElse(DEFAULT_CORRELATION_VARIABLE), correlationId);
        ObjectNode body = JsonNodeFactory.instance.objectNode();
        body.put("sysparm_quantity", "1");
        body.put("sysparm_requested_for", requesterSysId);
        body.set("variables", fields);
        return body;
    }

    private JsonNode submitCartWhenTwoStep(ServiceNowConfig.TicketProfile profile, JsonNode response) {
        boolean hasCart = !response.at("/result/cart_id").asText("").isBlank();
        if (!hasCart || !findText(response, numberPointers(profile)).isBlank()) {
            return response;
        }
        if (!profile.submitCartWhenTwoStep()) {
            throw new InternalException("Catalog order returned a cart (two-step checkout); "
                    + "enable submit-cart-when-two-step or disable two-step checkout");
        }
        return client.send("POST", SUBMIT_ORDER_PATH, JsonNodeFactory.instance.objectNode());
    }

    private TicketReference toReference(ServiceNowConfig.TicketProfile profile, JsonNode response) {
        String number = requireText(response, numberPointers(profile), "number");
        String sysId = requireText(response, sysIdPointers(profile), "sys_id");
        ServiceNowConfig.ResponseMapping mapping = profile.response();
        String table = mapping.tablePointer().map(p -> response.at(p).asText("")).filter(t -> !t.isBlank())
                .orElse(mapping.table().orElse("sc_request"));
        return new TicketReference(number, sysId, table, client.ticketLink(table, sysId));
    }

    private String[] numberPointers(ServiceNowConfig.TicketProfile profile) {
        return profile.response().numberPointer().map(p -> new String[]{p})
                .orElse(new String[]{"/result/request_number", "/result/number"});
    }

    private String[] sysIdPointers(ServiceNowConfig.TicketProfile profile) {
        return profile.response().sysIdPointer().map(p -> new String[]{p})
                .orElse(new String[]{"/result/request_id", "/result/sys_id"});
    }

    private String target(ServiceNowConfig.TicketProfile profile) {
        return profile.target().orElseThrow(() -> new InternalException("Service Catalog profile requires a catalog item target"));
    }

    private String findText(JsonNode response, String[] pointers) {
        for (String pointer : pointers) {
            String value = response.at(pointer).asText("");
            if (!value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private String requireText(JsonNode response, String[] pointers, String what) {
        String value = response == null ? "" : findText(response, pointers);
        if (value.isBlank()) {
            throw new InternalException("ServiceNow response did not contain the ticket " + what + " at " + pointers[0]);
        }
        return value;
    }
}
