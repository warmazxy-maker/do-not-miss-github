package com.donotmiss.backend.agentlog;

public enum AgentBadCaseIssueType {
    UNKNOWN,
    RETRIEVAL_MISS,
    LOW_RELEVANCE,
    QUERY_REWRITE_ERROR,
    HALLUCINATION,
    WRONG_CONTEXT,
    PLAN_UNEXECUTABLE,
    COACH_OFF_TOPIC,
    SCORING_UNFAIR,
    TOOL_ERROR,
    UI_CONFUSION,
    LATENCY_TIMEOUT
}
