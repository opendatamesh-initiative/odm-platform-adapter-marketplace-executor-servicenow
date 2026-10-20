package org.opendatamesh.platform.adapter.marketplace.executor.starter.marketplace.domain;

import java.util.Map;

public record TicketEvent(String correlationId, Map<String, String> fields) {

    public TicketEvent {
        fields = Map.copyOf(fields == null ? Map.of() : fields);
    }
}
