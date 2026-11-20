package com.donotmiss.backend.agentkit;

import com.donotmiss.backend.agentkit.actions.ConfirmableTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Every tool, collected from the Spring context at startup (F0 decision 1: code is the only
 * source). A broken definition stops the application from starting, the same way a broken
 * migration does, instead of failing on the first student who triggers it.
 *
 * <p>The only runtime switch is {@code app.agent.disabled-tools}: a comma-separated list of tool
 * names to turn off without a deploy (a restart is enough).</p>
 */
@Component
public class ToolRegistry {
    private static final Logger log = LoggerFactory.getLogger(ToolRegistry.class);
    static final Pattern NAME = Pattern.compile("^[a-z][a-z0-9_]{1,63}$");

    private final Map<String, AgentTool> tools;
    private final Set<String> disabled;

    public ToolRegistry(List<AgentTool> tools,
                        @Value("${app.agent.disabled-tools:}") List<String> disabled) {
        Map<String, AgentTool> byName = new LinkedHashMap<>();
        List<String> problems = new ArrayList<>();
        for (AgentTool tool : tools) {
            ToolDefinition definition = tool.definition();
            String name = definition == null ? null : definition.name();
            String where = tool.getClass().getSimpleName();
            if (definition == null || name == null || !NAME.matcher(name).matches()) {
                problems.add(where + ": name must match " + NAME.pattern());
                continue;
            }
            if (byName.putIfAbsent(name, tool) != null) {
                problems.add(where + ": duplicate tool name '" + name + "'");
            }
            if (definition.version() < 1) {
                problems.add(name + ": version must be >= 1");
            }
            if (isBlank(definition.title()) || isBlank(definition.description())) {
                problems.add(name + ": title and description are required");
            }
            if (definition.risk() == null || definition.permission() == null) {
                problems.add(name + ": risk and permission are required");
            } else if (definition.risk().modelUsage() == ToolRisk.ModelUsage.CALLS_MODEL
                    && definition.rateLimitTier() == null) {
                problems.add(name + ": a tool that calls a model must have a rate-limit tier");
            }
            if (definition.risk() != null) {
                boolean confirmWrite = definition.risk().dataEffect() == ToolRisk.DataEffect.CONFIRM_WRITE;
                if (confirmWrite != (tool instanceof ConfirmableTool)) {
                    problems.add(name + ": a CONFIRM_WRITE tool must implement ConfirmableTool, and only such a tool");
                } else if (confirmWrite && (definition.parameters() == null || !definition.parameters().path("properties")
                        .has(ConfirmableTool.REPLACES_ARGUMENT))) {
                    problems.add(name + ": a confirmable tool must accept '" + ConfirmableTool.REPLACES_ARGUMENT + "'");
                }
            }
            if (definition.timeout() == null || definition.timeout().isNegative() || definition.timeout().isZero()) {
                problems.add(name + ": timeout must be positive");
            }
            ToolSchemas.problemsIn(definition.parameters())
                    .forEach(problem -> problems.add(name + " schema " + problem));
        }
        if (!problems.isEmpty()) {
            throw new IllegalStateException("Invalid agent tools:\n  " + String.join("\n  ", problems));
        }
        this.tools = Map.copyOf(byName);
        this.disabled = disabled == null ? Set.of() : disabled.stream()
                .map(String::trim).filter(name -> !name.isEmpty()).collect(Collectors.toUnmodifiableSet());
        this.disabled.stream().filter(name -> !this.tools.containsKey(name))
                .forEach(name -> log.warn("app.agent.disabled-tools names an unknown tool '{}'.", name));
        log.info("Agent tool registry: {} tools, disabled={}", this.tools.size(), this.disabled);
    }

    public Optional<AgentTool> find(String name) {
        return Optional.ofNullable(name == null ? null : tools.get(name));
    }

    public boolean contains(String name) {
        return tools.containsKey(name);
    }

    public boolean isEnabled(String name) {
        return contains(name) && !disabled.contains(name);
    }

    /** Sorted by name, for stable display. */
    public List<AgentTool> all() {
        return tools.values().stream()
                .sorted(java.util.Comparator.comparing(tool -> tool.definition().name()))
                .toList();
    }

    public Collection<String> names() {
        return tools.keySet();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
