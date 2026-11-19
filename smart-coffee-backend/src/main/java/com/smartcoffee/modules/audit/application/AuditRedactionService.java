package com.smartcoffee.modules.audit.application;

import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.Set;

@Component
public class AuditRedactionService {

    private static final Set<String> SENSITIVE = Set.of(
            "password",
            "passwordHash",
            "accessToken",
            "refreshToken",
            "secret",
            "authorization",
            "deviceToken",
            "privateKey"
    );

    private final ObjectMapper json;

    public AuditRedactionService(ObjectMapper json) {
        this.json = json;
    }

    public String safe(Object value) {
        if (value == null) {
            return null;
        }

        JsonNode node = json.valueToTree(value);
        redact(node);

        return node.toString();
    }

    private void redact(JsonNode node) {
        if (node == null || node.isNull()) {
            return;
        }

        if (node.isObject()) {
            ObjectNode objectNode = (ObjectNode) node;
            var fieldNames = new ArrayList<String>();

            objectNode.properties()
                    .forEach(entry -> fieldNames.add(entry.getKey()));

            for (String fieldName : fieldNames) {
                if (isSensitive(fieldName)) {
                    objectNode.put(fieldName, "[REDACTED]");
                } else {
                    redact(objectNode.get(fieldName));
                }
            }

            return;
        }

        if (node.isArray()) {
            node.forEach(this::redact);
        }
    }

    private boolean isSensitive(String fieldName) {
        return SENSITIVE.stream()
                .anyMatch(name -> name.equalsIgnoreCase(fieldName));
    }
}