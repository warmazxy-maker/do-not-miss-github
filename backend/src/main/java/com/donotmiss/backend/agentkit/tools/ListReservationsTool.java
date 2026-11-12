package com.donotmiss.backend.agentkit.tools;

import com.donotmiss.backend.access.Permission;
import com.donotmiss.backend.agentkit.AgentTool;
import com.donotmiss.backend.agentkit.ToolCaller;
import com.donotmiss.backend.agentkit.ToolDefinition;
import com.donotmiss.backend.agentkit.ToolOutput;
import com.donotmiss.backend.agentkit.ToolResult;
import com.donotmiss.backend.agentkit.ToolRisk;
import com.donotmiss.backend.reservation.ReservationDtos;
import com.donotmiss.backend.reservation.ReservationService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The student's current reservations. Bounded (only the active ones), so it returns all of them.
 * Pruned: the check-in token (a credential, never for the model), the event body text and
 * internal ids of the organiser.
 */
@Component
public class ListReservationsTool implements AgentTool {
    private static final ToolDefinition DEFINITION = new ToolDefinition(
            "list_reservations", 1, "查预约",
            "查询学生当前所有有效的预约（已预约、还没有签到完成的活动）：活动名称、组织、时间、地点，"
                    + "以及活动是否已开始、已结束或已下架。不需要参数。",
            ToolParams.NONE, ToolRisk.readOnly(), Permission.STUDENT_FEATURES, null,
            ToolDefinition.DEFAULT_TIMEOUT);

    private final ReservationService reservationService;
    private final ObjectMapper objectMapper;
    private final ZoneId zone;

    public ListReservationsTool(ReservationService reservationService, ObjectMapper objectMapper,
                                @Value("${app.mcp.default-timezone:Asia/Tokyo}") String timezone) {
        this.reservationService = reservationService;
        this.objectMapper = objectMapper;
        this.zone = ZoneId.of(timezone);
    }

    @Override
    public ToolDefinition definition() {
        return DEFINITION;
    }

    @Override
    public ToolResult execute(JsonNode arguments, ToolCaller caller) {
        List<ReservationDtos.ReservationResponse> reservations = reservationService.listActive(caller.userId());
        return ToolOutput.list(objectMapper, reservations, this::fields, "查到 " + reservations.size() + " 个预约");
    }

    private Map<String, Object> fields(ReservationDtos.ReservationResponse reservation) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("reservationId", reservation.id());
        fields.put("reservedAt", ToolOutput.time(reservation.reservedAt(), zone));
        var event = reservation.event();
        if (event != null) {
            fields.put("eventId", event.id());
            fields.put("title", event.title());
            fields.put("organization", event.organizationName());
            fields.put("category", event.category());
            fields.put("start", ToolOutput.time(event.startTime()));
            fields.put("end", ToolOutput.time(event.endTime()));
            fields.put("location", event.location());
            fields.put("phase", event.timePhaseLabel());
            fields.put("eventStatus", event.studentStatusLabel());
            fields.put("benefit", event.benefitType());
            fields.put("skill", event.skill());
        }
        fields.put("notice", reservation.notice());
        fields.values().removeIf(java.util.Objects::isNull);
        return fields;
    }
}
