package com.donotmiss.backend.agentlog;

public enum AgentRunType {
    EVENT_RECOMMENDATION,
    PLAN_RECOMMENDATION,
    GROWTH_TAG_EXTRACTION,
    EVENT_QUALITY_ANALYSIS,
    ABILITY_EVIDENCE_ASSESSMENT,
    ABILITY_JUDGE,
    BAD_CASE_INTAKE,
    GUIDE_ACTION_DECISION,
    GUIDE_CONVERSATION,
    GUIDE_CONTEXT_COMPRESSION,
    RESUME_PARSER,
    RESUME_INTERVIEWER,
    RESUME_ANSWER_EVALUATOR,
    RESUME_ABILITY_SCORING,
    CAPABILITY_V2_PIPELINE,
    /** F0: an admin trying an agent in the ops playground. */
    AGENT_PLAYGROUND,
    /** F1: a student talking to the master agent. */
    MASTER_AGENT,
    /** F1: older Tandem messages folded into the rolling summary. */
    MASTER_CONTEXT_COMPRESSION
}
