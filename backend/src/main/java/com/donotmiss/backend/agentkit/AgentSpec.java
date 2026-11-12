package com.donotmiss.backend.agentkit;

import com.donotmiss.backend.ratelimit.RateLimitTier;

import java.util.List;

/**
 * An agent as the registry knows it (F0 decision 2). The agent lists the tools it may use, so one
 * tool can serve several agents and adding an agent does not touch existing tools.
 */
public interface AgentSpec {
    /** Lower snake case, unique. */
    String name();

    String title();

    /** Recorded on every run so behaviour can be tied to a prompt version. */
    String promptVersion();

    String systemPrompt();

    List<String> toolNames();

    /** Tool rounds per turn; after that the agent answers with what it has. */
    int maxSteps();

    /** Charged once per user turn. */
    RateLimitTier rateLimitTier();
}
