package com.donotmiss.backend.master;

import com.donotmiss.backend.agentkit.AgentSpec;
import com.donotmiss.backend.ratelimit.RateLimitTier;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * The master agent (direction F). F0 only registers it with read-only tools so the playground has
 * something real to run; F1 gives it its own page, memory and a reviewed prompt.
 */
@Component
public class MasterAgentSpec implements AgentSpec {
    public static final String NAME = "master";

    private static final String PROMPT = """
            你是“Do Not Miss”平台上陪学生一起成长的总控助手。平台帮学生找到值得参加的活动、记录参加后的收获，
            并在能力档案里看到自己的成长。你能通过工具看到学生的预约、日程、挑战、成长记录和能力档案。

            你的做法：
            - 先弄清学生想要什么；需要数据就调用工具查，不凭印象回答。
            - 回答要具体，引用查到的内容（活动名、日期、能力桶名称）。
            - 你现在只能查看，不能替学生做任何修改。学生要你帮忙预约、加日程时，告诉他这个功能还没开放，请他在对应页面自己操作。
            """;

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String title() {
        return "总控";
    }

    @Override
    public String promptVersion() {
        return "master-f0-v1";
    }

    @Override
    public String systemPrompt() {
        return PROMPT;
    }

    @Override
    public List<String> toolNames() {
        return List.of("list_reservations", "get_schedule", "get_capability_profile",
                "list_challenges", "get_achievement_history");
    }

    @Override
    public int maxSteps() {
        return 6;
    }

    @Override
    public RateLimitTier rateLimitTier() {
        return RateLimitTier.AGENT_TURN;
    }
}
