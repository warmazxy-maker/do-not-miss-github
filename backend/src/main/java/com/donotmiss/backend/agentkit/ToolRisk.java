package com.donotmiss.backend.agentkit;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * How risky a tool is, as independent dimensions (F0 decision 3). Version 1 has two:
 * whether it changes data, and whether it calls a model.
 *
 * <p>To add a dimension later: add an enum and a component here, and one line in
 * {@link #dimensions()}. The ops panel reads {@code dimensions()}, so it picks the new dimension
 * up without changes.</p>
 */
public record ToolRisk(DataEffect dataEffect, ModelUsage modelUsage) {

    public enum DataEffect {
        /** Only reads; runs immediately. */
        READ("只读"),
        /** Changes the student's data; never runs directly, becomes a confirmation card (F2). */
        CONFIRM_WRITE("需确认的写入"),
        /** Changes only the agent's own data (inbox, its memory); runs immediately. */
        INTERNAL_WRITE("内部写入");

        private final String label;

        DataEffect(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    public enum ModelUsage {
        NONE("不调用模型"),
        CALLS_MODEL("会调用模型");

        private final String label;

        ModelUsage(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    public ToolRisk {
        if (dataEffect == null || modelUsage == null) {
            throw new IllegalArgumentException("Every risk dimension must be set.");
        }
    }

    public static ToolRisk readOnly() {
        return new ToolRisk(DataEffect.READ, ModelUsage.NONE);
    }

    /** Dimension name to value label, in a fixed order; what the ops panel shows. */
    public Map<String, String> dimensions() {
        Map<String, String> dimensions = new LinkedHashMap<>();
        dimensions.put("数据", dataEffect.label());
        dimensions.put("模型", modelUsage.label());
        return dimensions;
    }
}
