package com.donotmiss.backend.agentkit.tools;

import com.donotmiss.backend.access.Permission;
import com.donotmiss.backend.agentkit.AgentTool;
import com.donotmiss.backend.agentkit.ToolCaller;
import com.donotmiss.backend.agentkit.ToolDefinition;
import com.donotmiss.backend.agentkit.ToolOutput;
import com.donotmiss.backend.agentkit.ToolResult;
import com.donotmiss.backend.agentkit.ToolRisk;
import com.donotmiss.backend.event.EventDtos;
import com.donotmiss.backend.event.EventEntity;
import com.donotmiss.backend.event.EventRepository;
import com.donotmiss.backend.event.EventReviewStatus;
import com.donotmiss.backend.reservation.ReservationRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The full text of one event. Students may see what was ever published (on the shelf or taken
 * down) and anything they reserved; drafts and events still in review are "not found", the same
 * as on the events page.
 */
@Component
public class GetEventDetailTool implements AgentTool {
    private static final ToolDefinition DEFINITION = new ToolDefinition(
            "get_event_detail", 1, "看活动详情",
            "查看一个活动的完整介绍：内容、时间、地点、组织、收获类型，以及现在是否还能参加。"
                    + "event_id 来自搜活动、查预约等工具的结果。",
            ToolParams.parse("""
                    {
                      "type": "object",
                      "properties": {
                        "event_id": {"type": "integer", "minimum": 1}
                      },
                      "required": ["event_id"],
                      "additionalProperties": false
                    }
                    """),
            ToolRisk.readOnly(), Permission.STUDENT_FEATURES, null, ToolDefinition.DEFAULT_TIMEOUT);

    private final EventRepository eventRepository;
    private final ReservationRepository reservationRepository;
    private final ObjectMapper objectMapper;

    public GetEventDetailTool(EventRepository eventRepository, ReservationRepository reservationRepository,
                              ObjectMapper objectMapper) {
        this.eventRepository = eventRepository;
        this.reservationRepository = reservationRepository;
        this.objectMapper = objectMapper;
    }

    @Override
    public ToolDefinition definition() {
        return DEFINITION;
    }

    @Override
    public String describe(JsonNode arguments) {
        return "#" + arguments.path("event_id").asLong();
    }

    @Override
    @Transactional(readOnly = true)
    public ToolResult execute(JsonNode arguments, ToolCaller caller) {
        long eventId = arguments.path("event_id").asLong();
        Optional<EventEntity> found = eventRepository.findById(eventId)
                .filter(event -> visible(event, caller.userId()));
        if (found.isEmpty()) {
            return ToolResult.error("没有编号为 " + eventId + " 的活动，或者学生看不到它。", "没找到活动 #" + eventId);
        }
        EventDtos.EventResponse event = EventDtos.EventResponse.from(found.get(), LocalDateTime.now());
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("eventId", event.id());
        fields.put("title", event.title());
        fields.put("organization", event.organizationName());
        fields.put("category", event.category());
        fields.put("start", ToolOutput.time(event.startTime()));
        fields.put("end", ToolOutput.time(event.endTime()));
        fields.put("location", event.location());
        fields.put("status", event.studentStatusLabel());
        fields.put("notice", event.studentNotice());
        fields.put("benefit", event.benefitType());
        fields.put("skill", event.skill());
        fields.put("money", event.moneyAmount());
        fields.put("content", ToolOutput.clip(event.content()));
        fields.put("reservedByStudent", reservationRepository.findByUserIdAndEventId(caller.userId(), eventId).isPresent());
        fields.values().removeIf(Objects::isNull);
        return ToolOutput.object(objectMapper, fields, "看了「" + event.title() + "」");
    }

    private boolean visible(EventEntity event, String userId) {
        EventReviewStatus status = event.getReviewStatus();
        return status == EventReviewStatus.APPROVED || status == EventReviewStatus.OFFLINE
                || reservationRepository.findByUserIdAndEventId(userId, event.getId()).isPresent();
    }
}
