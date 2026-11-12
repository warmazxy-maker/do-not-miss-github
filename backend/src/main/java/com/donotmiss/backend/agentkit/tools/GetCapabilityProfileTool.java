package com.donotmiss.backend.agentkit.tools;

import com.donotmiss.backend.access.Permission;
import com.donotmiss.backend.agentkit.AgentTool;
import com.donotmiss.backend.agentkit.ToolCaller;
import com.donotmiss.backend.agentkit.ToolDefinition;
import com.donotmiss.backend.agentkit.ToolOutput;
import com.donotmiss.backend.agentkit.ToolResult;
import com.donotmiss.backend.agentkit.ToolRisk;
import com.donotmiss.backend.capabilityv2.CapabilityBucketType;
import com.donotmiss.backend.capabilityv2.CapabilityV2ProfileService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** The whole capability profile (V2 buckets). Bounded, so every bucket is returned. */
@Component
public class GetCapabilityProfileTool implements AgentTool {
    private static final ToolDefinition DEFINITION = new ToolDefinition(
            "get_capability_profile", 1, "查能力档案",
            "读取学生的能力档案：每个能力桶（分“知识与技术”和“核心职能”两类）的能力分、经验值、当前阶段、"
                    + "证据数量、是否正在等待晋级复核。能力分越高越强；不确定度越高，说明证据越少、分数越不可靠。不需要参数。",
            ToolParams.NONE, ToolRisk.readOnly(), Permission.STUDENT_FEATURES, null,
            ToolDefinition.DEFAULT_TIMEOUT);

    private final CapabilityV2ProfileService profileService;
    private final ObjectMapper objectMapper;
    private final ZoneId zone;

    public GetCapabilityProfileTool(CapabilityV2ProfileService profileService, ObjectMapper objectMapper,
                                    @Value("${app.mcp.default-timezone:Asia/Tokyo}") String timezone) {
        this.profileService = profileService;
        this.objectMapper = objectMapper;
        this.zone = ZoneId.of(timezone);
    }

    @Override
    public ToolDefinition definition() {
        return DEFINITION;
    }

    @Override
    public ToolResult execute(JsonNode arguments, ToolCaller caller) {
        CapabilityV2ProfileService.CapabilityProfile profile = profileService.profileForUser(caller.userId());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("totalEvidenceCount", profile.totalEvidenceCount());
        data.put("lastUpdatedAt", ToolOutput.time(profile.lastUpdatedAt(), zone));
        data.put("knowledgeBuckets", buckets(profile.knowledgeBuckets()));
        data.put("functionBuckets", buckets(profile.functionBuckets()));
        int count = profile.knowledgeBuckets().size() + profile.functionBuckets().size();
        return ToolOutput.object(objectMapper, data,
                count == 0 ? "能力档案还是空的" : "读到 " + count + " 个能力桶");
    }

    private List<Map<String, Object>> buckets(List<CapabilityV2ProfileService.CapabilitySummary> summaries) {
        return summaries.stream().map(this::fields).toList();
    }

    private Map<String, Object> fields(CapabilityV2ProfileService.CapabilitySummary bucket) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("bucketId", bucket.bucketId());
        fields.put("name", bucket.displayName());
        fields.put("kind", bucket.bucketType() == CapabilityBucketType.KNOWLEDGE ? "知识与技术" : "核心职能");
        fields.put("domain", bucket.domainName());
        fields.put("group", bucket.groupName());
        fields.put("description", ToolOutput.clip(bucket.description()));
        fields.put("rank", bucket.rankName());
        fields.put("abilityScore", bucket.abilityScore());
        fields.put("uncertainty", bucket.abilityUncertainty());
        fields.put("experience", bucket.experienceScore());
        fields.put("evidenceCount", bucket.evidenceCount());
        if (bucket.promotionPending()) {
            fields.put("pendingRank", bucket.pendingRank());
        }
        fields.put("updatedAt", ToolOutput.time(bucket.updatedAt(), zone));
        fields.values().removeIf(Objects::isNull);
        return fields;
    }
}
