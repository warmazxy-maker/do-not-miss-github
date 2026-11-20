package com.donotmiss.backend.master.wake;

import com.donotmiss.backend.agentkit.AgentSpec;
import com.donotmiss.backend.ratelimit.RateLimitTier;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * F4: Tandem woken by the platform instead of by the student. Same character, read tools and
 * the inbox only: it cannot propose cards while the student is away (F4 decision 6), and it may
 * decide that nothing is worth saying.
 */
@Component
public class WakeAgentSpec implements AgentSpec {
    public static final String NAME = "master_wake";

    private static final String PROMPT = """
            你是 Tandem，“Do Not Miss”平台上陪学生一起成长的伙伴。现在不是学生在和你说话：平台上刚发生了一些和学生
            有关的事，系统把你叫醒，由你决定要不要在你们的对话里主动给学生留一句话。

            【怎么决定】
            - 值得说，就用 leave_message 留一条；只留一条，几件事合在一起说。
            - 不值得说（事情太小，或者你最近刚说过差不多的话），就什么都不留，直接回答“不留言”。

            【怎么说】
            - 像朋友发消息：简短（一般不超过 150 字）、具体、有依据；不说教，不堆鼓励的套话，不用标题和列表。
            - 联系学生的目标：这件事是某个目标的一步，就说说进度；目标的步骤都完成了，可以问学生要不要把目标标为完成。
            - 活动签到成功：可以邀请他写几句心得，写下的心得会成为能力证据。
            - 能力晋级：说清楚哪项能力升到了哪一级；需要的话查成长记录，说说是哪些经历带来的。
            - 回顾邀请：简单说说目标进展，问他有没有空一起看看。
            - 活动提醒：说清楚时间和地点。
            - 这次你不能替学生做任何事，也不能出确认卡片；需要的话告诉他“回复我就能帮你……”。
            - 说到学生的预约、日程、目标时，以系统给你的目标列表和你刚查到的数据为准；对话里旧回答说过的情况
              （比如“那天有个预约”）可能已经变了，没查到的不要提。
            """;

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String title() {
        return "Tandem（主动留言）";
    }

    @Override
    public String promptVersion() {
        return "tandem-wake-v2";
    }

    @Override
    public String systemPrompt() {
        return PROMPT;
    }

    @Override
    public List<String> toolNames() {
        return List.of(
                "list_reservations", "get_schedule", "get_capability_profile", "list_challenges",
                "get_achievement_history", "get_event_detail", "get_preference_profile", "get_goals",
                "leave_message");
    }

    @Override
    public int maxSteps() {
        return 4;
    }

    @Override
    public RateLimitTier rateLimitTier() {
        return RateLimitTier.AGENT_TURN;
    }
}
