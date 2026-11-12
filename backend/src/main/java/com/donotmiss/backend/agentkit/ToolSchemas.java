package com.donotmiss.backend.agentkit;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A small JSON Schema subset: enough for tool arguments, without a new dependency.
 *
 * <p>Two jobs. At startup, {@link #problemsIn} rejects schemas that use a keyword this class does
 * not understand, so a tool cannot silently rely on a check that never runs. At call time,
 * {@link #validate} checks the model's arguments. This is a structural guard only; deciding which
 * tool to call and with what meaning stays with the model.</p>
 *
 * <p>Supported: {@code type} (object, string, integer, number, boolean, array), {@code properties},
 * {@code required}, {@code additionalProperties: false}, {@code enum}, {@code minimum},
 * {@code maximum}, {@code minLength}, {@code maxLength}, {@code format: "date"} (YYYY-MM-DD),
 * {@code items}, plus the annotations {@code description} and {@code title}.</p>
 */
public final class ToolSchemas {
    private static final Set<String> KEYWORDS = Set.of(
            "type", "properties", "required", "additionalProperties", "enum", "minimum", "maximum",
            "minLength", "maxLength", "format", "items", "description", "title");
    private static final Set<String> TYPES = Set.of("object", "string", "integer", "number", "boolean", "array");

    private ToolSchemas() {
    }

    /** Empty when the schema is usable; a tool's top-level schema must be an object. */
    public static List<String> problemsIn(JsonNode schema) {
        List<String> problems = new ArrayList<>();
        if (schema == null || !schema.isObject() || !"object".equals(schema.path("type").asText())) {
            problems.add("top level must be {\"type\": \"object\"}");
            return problems;
        }
        checkSchema(schema, "$", problems);
        return problems;
    }

    private static void checkSchema(JsonNode schema, String path, List<String> problems) {
        if (!schema.isObject()) {
            problems.add(path + ": schema must be an object");
            return;
        }
        Iterator<String> names = schema.fieldNames();
        while (names.hasNext()) {
            String keyword = names.next();
            if (!KEYWORDS.contains(keyword)) {
                problems.add(path + ": unsupported keyword '" + keyword + "'");
            }
        }
        String type = schema.path("type").asText("");
        if (!TYPES.contains(type)) {
            problems.add(path + ": type must be one of " + TYPES);
        }
        if (schema.has("format") && !"date".equals(schema.path("format").asText())) {
            problems.add(path + ": only format 'date' is supported");
        }
        if (schema.has("additionalProperties") && !schema.path("additionalProperties").isBoolean()) {
            problems.add(path + ": additionalProperties must be true or false");
        }
        if ("object".equals(type)) {
            JsonNode properties = schema.path("properties");
            if (!properties.isMissingNode() && !properties.isObject()) {
                problems.add(path + ".properties must be an object");
            }
            for (Iterator<Map.Entry<String, JsonNode>> it = properties.fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> entry = it.next();
                checkSchema(entry.getValue(), path + "." + entry.getKey(), problems);
            }
            for (JsonNode required : schema.path("required")) {
                if (!properties.has(required.asText())) {
                    problems.add(path + ": required '" + required.asText() + "' is not a property");
                }
            }
        }
        if ("array".equals(type)) {
            if (!schema.has("items")) {
                problems.add(path + ": array needs items");
            } else {
                checkSchema(schema.path("items"), path + "[]", problems);
            }
        }
    }

    /** Empty when the value fits; otherwise plain Chinese reasons the model can act on. */
    public static List<String> validate(JsonNode schema, JsonNode value) {
        List<String> errors = new ArrayList<>();
        validate(schema, value, "参数", errors);
        return errors;
    }

    private static void validate(JsonNode schema, JsonNode value, String path, List<String> errors) {
        String type = schema.path("type").asText("");
        if (!typeMatches(type, value)) {
            errors.add(path + " 应该是 " + typeLabel(type));
            return;
        }
        if (schema.has("enum")) {
            boolean found = false;
            for (JsonNode option : schema.path("enum")) {
                found |= option.equals(value);
            }
            if (!found) {
                errors.add(path + " 只能是 " + schema.path("enum"));
            }
        }
        switch (type) {
            case "object" -> {
                JsonNode properties = schema.path("properties");
                for (JsonNode required : schema.path("required")) {
                    JsonNode field = value.get(required.asText());
                    if (field == null || field.isNull()) {
                        errors.add("缺少 " + required.asText());
                    }
                }
                for (Iterator<Map.Entry<String, JsonNode>> it = value.fields(); it.hasNext(); ) {
                    Map.Entry<String, JsonNode> entry = it.next();
                    JsonNode fieldSchema = properties.get(entry.getKey());
                    if (fieldSchema == null) {
                        if (!schema.path("additionalProperties").asBoolean(true)) {
                            errors.add("不认识的参数 " + entry.getKey());
                        }
                    } else if (!entry.getValue().isNull()) {
                        validate(fieldSchema, entry.getValue(), entry.getKey(), errors);
                    }
                }
            }
            case "string" -> {
                String text = value.asText();
                if (schema.has("minLength") && text.length() < schema.path("minLength").asInt()) {
                    errors.add(path + " 至少 " + schema.path("minLength").asInt() + " 个字");
                }
                if (schema.has("maxLength") && text.length() > schema.path("maxLength").asInt()) {
                    errors.add(path + " 最多 " + schema.path("maxLength").asInt() + " 个字");
                }
                if ("date".equals(schema.path("format").asText(""))) {
                    try {
                        LocalDate.parse(text);
                    } catch (DateTimeParseException ex) {
                        errors.add(path + " 应该是 YYYY-MM-DD 格式的日期");
                    }
                }
            }
            case "integer", "number" -> {
                double number = value.asDouble();
                if (schema.has("minimum") && number < schema.path("minimum").asDouble()) {
                    errors.add(path + " 不能小于 " + schema.path("minimum").asText());
                }
                if (schema.has("maximum") && number > schema.path("maximum").asDouble()) {
                    errors.add(path + " 不能大于 " + schema.path("maximum").asText());
                }
            }
            case "array" -> {
                int index = 0;
                for (JsonNode item : value) {
                    validate(schema.path("items"), item, path + "[" + index++ + "]", errors);
                }
            }
            default -> {
            }
        }
    }

    private static boolean typeMatches(String type, JsonNode value) {
        return switch (type) {
            case "object" -> value.isObject();
            case "string" -> value.isTextual();
            case "integer" -> value.isIntegralNumber();
            case "number" -> value.isNumber();
            case "boolean" -> value.isBoolean();
            case "array" -> value.isArray();
            default -> false;
        };
    }

    private static String typeLabel(String type) {
        return switch (type) {
            case "object" -> "一个对象";
            case "string" -> "文字";
            case "integer" -> "整数";
            case "number" -> "数字";
            case "boolean" -> "true 或 false";
            case "array" -> "列表";
            default -> type;
        };
    }
}
