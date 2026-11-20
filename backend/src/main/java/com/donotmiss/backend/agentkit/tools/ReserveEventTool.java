package com.donotmiss.backend.agentkit.tools;

import com.donotmiss.backend.access.Permission;
import com.donotmiss.backend.agentkit.ToolCaller;
import com.donotmiss.backend.agentkit.ToolDefinition;
import com.donotmiss.backend.agentkit.ToolRisk;
import com.donotmiss.backend.agentkit.actions.ActionCard;
import com.donotmiss.backend.agentkit.actions.ConfirmableTool;
import com.donotmiss.backend.common.ApiException;
import com.donotmiss.backend.event.EventEntity;
import com.donotmiss.backend.event.EventReviewStatus;
import com.donotmiss.backend.event.EventService;
import com.donotmiss.backend.reservation.ReservationDtos;
import com.donotmiss.backend.reservation.ReservationEntity;
import com.donotmiss.backend.reservation.ReservationRepository;
import com.donotmiss.backend.reservation.ReservationService;
import com.donotmiss.backend.reservation.ReservationStatus;
import com.donotmiss.backend.schedule.ScheduleService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * F2: reserve an event. The card is built from the event in the database, so a wrong id shows the
 * wrong event's real title, never the model's description of it.
 *
 * <p>Only a new reservation becomes a card: if the student already holds one, the service would
 * just hand it back, and a rollback would then cancel a reservation this action never made.</p>
 */
@Component
public class ReserveEventTool implements ConfirmableTool {
    private static final ToolDefinition DEFINITION = new ToolDefinition(
            "reserve_event", 1, "预约活动",
            "为学生预约一个活动，event_id 来自“搜活动”或“看活动详情”的结果。预约后活动会自动加进日程表。"
                    + "不会直接执行：会生成一张卡片，学生点确认后才预约。",
            ToolParams.parse("""
                    {
                      "type": "object",
                      "properties": {
                        "event_id": {"type": "integer", "minimum": 1, "description": "活动 id"},
                        %s
                      },
                      "required": ["event_id"],
                      "additionalProperties": false
                    }
                    """.formatted(WriteSupport.REPLACES_SCHEMA)),
            new ToolRisk(ToolRisk.DataEffect.CONFIRM_WRITE, ToolRisk.ModelUsage.NONE),
            Permission.STUDENT_FEATURES, null, ToolDefinition.DEFAULT_TIMEOUT);

    private final EventService eventService;
    private final ReservationService reservationService;
    private final ReservationRepository reservations;
    private final ScheduleService scheduleService;
    private final ObjectMapper objectMapper;

    public ReserveEventTool(EventService eventService, ReservationService reservationService,
                            ReservationRepository reservations, ScheduleService scheduleService,
                            ObjectMapper objectMapper) {
        this.eventService = eventService;
        this.reservationService = reservationService;
        this.reservations = reservations;
        this.scheduleService = scheduleService;
        this.objectMapper = objectMapper;
    }

    @Override
    public ToolDefinition definition() {
        return DEFINITION;
    }

    @Override
    public String describe(JsonNode arguments) {
        return "#" + arguments.path("event_id").asText("");
    }

    @Override
    public Proposal propose(JsonNode arguments, ToolCaller caller) {
        long eventId = arguments.path("event_id").asLong();
        EventEntity event = eventService.getEntity(eventId);
        if (event.getReviewStatus() != EventReviewStatus.APPROVED || event.hasEnded(LocalDateTime.now())) {
            throw ApiException.badRequest("活动「" + event.getTitle() + "」未上架或已结束，不能预约");
        }
        requireNotHeld(caller.userId(), eventId);

        List<ActionCard.Field> fields = new ArrayList<>();
        if (event.getOrganizationName() != null) {
            fields.add(new ActionCard.Field("主办方", event.getOrganizationName()));
        }
        fields.add(new ActionCard.Field("时间", WriteSupport.timeRange(event.getStartTime(), event.getEndTime())));
        if (event.getLocation() != null) {
            fields.add(new ActionCard.Field("地点", event.getLocation()));
        }
        List<String> warnings = event.getStartTime() == null || event.getEndTime() == null ? List.of()
                : WriteSupport.clashes(scheduleService, caller.userId(), event.getStartTime(), event.getEndTime(),
                        null, null, null);
        ActionCard card = new ActionCard("预约活动", event.getTitle(), fields,
                List.of("预约后会自动加进你的日程表", "到场后由主办方扫你的预约二维码签到"), warnings, "/student/reservations");
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("eventId", eventId);
        return new Proposal(card, payload);
    }

    @Override
    public Applied apply(JsonNode payload, String userId) {
        long eventId = payload.path("eventId").asLong();
        requireNotHeld(userId, eventId);
        ReservationDtos.ReservationResponse reserved = reservationService.reserve(userId, eventId);
        ObjectNode undo = objectMapper.createObjectNode();
        undo.put("reservationId", reserved.id());
        undo.put("reservedAt", stamp(reserved.reservedAt()));
        return new Applied("已预约，也加进了日程表", undo);
    }

    @Override
    public String undo(JsonNode payload, JsonNode undoData, String userId) {
        ReservationEntity reservation = reservations.findByIdAndUserId(undoData.path("reservationId").asLong(), userId)
                .orElseThrow(() -> ApiException.badRequest("这个预约已经不在了"));
        if (reservation.getStatus() != ReservationStatus.RESERVED) {
            throw ApiException.badRequest("这个预约已经取消或完成了，不能撤销");
        }
        if (!sameMoment(reservation.getReservedAt(), WriteSupport.text(undoData, "reservedAt"))) {
            throw ApiException.badRequest("这个活动之后又被重新预约过，不是这次的预约，没有撤销");
        }
        reservationService.cancel(userId, reservation.getId());
        return "已撤销：预约已取消，日程表里的这一项也删掉了";
    }

    private static String stamp(Instant instant) {
        return instant == null ? null : instant.toString();
    }

    /** Within a millisecond: the database rounds to microseconds, the clock may give nanoseconds. */
    static boolean sameMoment(Instant stored, String recorded) {
        if (stored == null || recorded == null) {
            return stored == null && recorded == null;
        }
        return Math.abs(Duration.between(stored, Instant.parse(recorded)).toNanos()) < 1_000_000;
    }

    private void requireNotHeld(String userId, long eventId) {
        reservations.findByUserIdAndEventId(userId, eventId).ifPresent(existing -> {
            if (existing.getStatus() == ReservationStatus.RESERVED) {
                throw ApiException.badRequest("你已经预约过这个活动了");
            }
            if (existing.getStatus() == ReservationStatus.COMPLETED) {
                throw ApiException.badRequest("这个活动你已经参加完了");
            }
        });
    }
}
