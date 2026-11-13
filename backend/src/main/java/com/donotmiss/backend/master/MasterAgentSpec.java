package com.donotmiss.backend.master;

import com.donotmiss.backend.agentkit.AgentSpec;
import com.donotmiss.backend.ratelimit.RateLimitTier;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * The master agent (direction F). Students know it as Tandem; in code it stays "master".
 * F1: its own page, long-term memory and nine read tools plus remember / forget.
 */
@Component
public class MasterAgentSpec implements AgentSpec {
    public static final String NAME = "master";

    private static final String PROMPT = """
            你是 Tandem，“Do Not Miss”平台上陪学生一起成长的伙伴。平台帮学生找到值得参加的活动，记录参加后的收获，
            并在能力档案里看到自己的成长。你的名字来自双人自行车：你和学生一起往前骑，方向由学生定。

            【你是谁】
            - 你是伙伴，不是老师：平等、具体、不说教，不打官腔，不堆砌鼓励的套话。
            - 你了解学生：长期记忆里有学生亲口说过的事，工具能查到他的预约、日程、挑战、成长记录、能力档案和偏好。
            - 你说话有依据：需要数据就先查，回答里引用查到的具体内容（活动名、日期、能力名称）。

            【怎么做】
            - 先弄清学生想要什么；不清楚就问一个最关键的问题，不要一次问一串。
            - 回答完可以顺手给一个和当前话题相关的小建议，但不要每次都推销平台功能。
            - 学生找活动时用“搜活动”；想了解某个活动的细节时用“看活动详情”。
            - 学生亲口说出关键事实（个人情况、约束、偏好、意向、约定）时，用 remember 记下来；说法变了就更新原来那条；
              学生要你忘掉某件事，或者一条记忆已经不对了，用 forget 删掉。记的时候不用特意告诉学生，除非他问。
            - 平台推断的偏好画像和学生亲口说的冲突时，以学生说的为准。

            【边界】
            - 你现在只能查看，不能替学生预约、加日程或修改任何东西。学生要你做这些时，告诉他这个功能还没开放，请他在对应页面自己操作。
            - 你不是心理咨询师。学生情绪很低落、提到伤害自己时，认真回应他，并建议他联系学校的心理咨询或身边信任的人。
            """;

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String title() {
        return "Tandem";
    }

    @Override
    public String promptVersion() {
        return "tandem-f1-v1";
    }

    @Override
    public String systemPrompt() {
        return PROMPT;
    }

    @Override
    public List<String> toolNames() {
        return List.of(
                "list_reservations", "get_schedule", "get_capability_profile", "list_challenges",
                "get_achievement_history", "search_events", "get_event_detail", "list_followed_organizations",
                "get_preference_profile", "remember", "forget");
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
