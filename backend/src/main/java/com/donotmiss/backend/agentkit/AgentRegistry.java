package com.donotmiss.backend.agentkit;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Every agent that uses tools, collected at startup. Checks that each agent only lists tools that
 * exist, so a typo in a tool list fails the start rather than a conversation.
 */
@Component
public class AgentRegistry {
    public static final int MAX_STEPS_CEILING = 10;

    private final Map<String, AgentSpec> agents;

    public AgentRegistry(List<AgentSpec> agents, ToolRegistry toolRegistry) {
        Map<String, AgentSpec> byName = new LinkedHashMap<>();
        List<String> problems = new ArrayList<>();
        for (AgentSpec agent : agents) {
            String name = agent.name();
            if (name == null || !ToolRegistry.NAME.matcher(name).matches()) {
                problems.add(agent.getClass().getSimpleName() + ": name must match " + ToolRegistry.NAME.pattern());
                continue;
            }
            if (byName.putIfAbsent(name, agent) != null) {
                problems.add("duplicate agent name '" + name + "'");
            }
            if (agent.maxSteps() < 1 || agent.maxSteps() > MAX_STEPS_CEILING) {
                problems.add(name + ": maxSteps must be between 1 and " + MAX_STEPS_CEILING);
            }
            if (agent.rateLimitTier() == null || agent.promptVersion() == null || agent.promptVersion().isBlank()) {
                problems.add(name + ": rateLimitTier and promptVersion are required");
            }
            for (String tool : agent.toolNames()) {
                if (!toolRegistry.contains(tool)) {
                    problems.add(name + ": unknown tool '" + tool + "'");
                }
            }
        }
        if (!problems.isEmpty()) {
            throw new IllegalStateException("Invalid agents:\n  " + String.join("\n  ", problems));
        }
        this.agents = Map.copyOf(byName);
    }

    public Optional<AgentSpec> find(String name) {
        return Optional.ofNullable(name == null ? null : agents.get(name));
    }

    public List<AgentSpec> all() {
        return agents.values().stream()
                .sorted(java.util.Comparator.comparing(AgentSpec::name))
                .toList();
    }

    /** Which agents may use the tool; what the ops panel shows in the other direction. */
    public List<String> agentsUsing(String toolName) {
        return all().stream().filter(agent -> agent.toolNames().contains(toolName)).map(AgentSpec::name).toList();
    }
}
