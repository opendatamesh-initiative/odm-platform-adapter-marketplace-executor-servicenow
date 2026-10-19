package org.opendatamesh.platform.adapter.marketplace.executor.starter.resources;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.util.Map;

@Schema(description = "Ticket event sent by ServiceNow when a ticket changes state")
public class TicketEventCommandRes {

    @Schema(description = "Marketplace request UUID stored in the ServiceNow ticket")
    private String correlationId;

    @Schema(description = "Ticket fields used by outcome rules and messages, e.g. state, close_code, close_notes, number, sys_id, table, closed_by")
    private Map<String, Object> fields;

    public String getCorrelationId() {
        return correlationId;
    }

    public void setCorrelationId(String correlationId) {
        this.correlationId = correlationId;
    }

    public Map<String, Object> getFields() {
        return fields;
    }

    public void setFields(Map<String, Object> fields) {
        this.fields = fields;
    }
}
