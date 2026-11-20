package com.donotmiss.backend.agentkit.tools;

import com.donotmiss.backend.agentkit.ToolCaller;
import com.donotmiss.backend.agentkit.actions.ConfirmableTool;
import com.donotmiss.backend.common.ApiException;
import com.donotmiss.backend.schedule.ScheduleDtos;
import com.donotmiss.backend.schedule.ScheduleItemEntity;
import com.donotmiss.backend.schedule.ScheduleItemRepository;
import com.donotmiss.backend.schedule.ScheduleItemStatus;
import com.donotmiss.backend.schedule.ScheduleItemType;
import com.donotmiss.backend.schedule.ScheduleService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Field;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** F2 write tools: the snapshot guards that keep a change or a rollback from overwriting the student's own edits. */
class WriteToolsTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String USER = "student";
    private static final ToolCaller CALLER = new ToolCaller(USER, "master", 1L);

    private final ScheduleService scheduleService = mock(ScheduleService.class);
    private final ScheduleItemRepository items = mock(ScheduleItemRepository.class);
    private final UpdateScheduleItemTool update = new UpdateScheduleItemTool(scheduleService, items, MAPPER);
    private final AddScheduleItemTool add = new AddScheduleItemTool(scheduleService, items, MAPPER);

    private ScheduleItemEntity item(long id, String title, String start, String end) {
        ScheduleItemEntity entity = new ScheduleItemEntity();
        entity.setUserId(USER);
        entity.setItemType(ScheduleItemType.CUSTOM);
        entity.setTitle(title);
        entity.setStartTime(LocalDateTime.parse(start));
        entity.setEndTime(LocalDateTime.parse(end));
        entity.setStatus(ScheduleItemStatus.ACTIVE);
        try {
            Field field = ScheduleItemEntity.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(entity, id);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException(ex);
        }
        when(items.findByIdAndUserId(id, USER)).thenReturn(Optional.of(entity));
        return entity;
    }

    private static JsonNode json(String text) throws Exception {
        return MAPPER.readTree(text);
    }

    @Test
    void aChangeCardShowsOldToNewAndRefusesNoChange() throws Exception {
        item(3, "英语角", "2026-11-21T14:00", "2026-11-21T15:00");
        when(scheduleService.list(USER, null)).thenReturn(List.of());

        ConfirmableTool.Proposal proposal = update.propose(json("{\"item_id\":3,\"start\":\"2026-11-21T15:00\","
                + "\"end\":\"2026-11-21T16:00\"}"), CALLER);

        assertThat(proposal.card().fields()).anySatisfy(field -> {
            assertThat(field.label()).isEqualTo("时间");
            assertThat(field.value()).contains("14:00").contains("→").contains("15:00–16:00");
        });
        assertThatThrownBy(() -> update.propose(json("{\"item_id\":3,\"title\":\"英语角\"}"), CALLER))
                .isInstanceOf(ApiException.class).hasMessageContaining("没有要改的内容");
    }

    @Test
    void aChangeDoesNotRunIfTheItemWasEditedAfterTheCardWasMade() throws Exception {
        ScheduleItemEntity entity = item(3, "英语角", "2026-11-21T14:00", "2026-11-21T15:00");
        when(scheduleService.list(USER, null)).thenReturn(List.of());
        JsonNode payload = update.propose(json("{\"item_id\":3,\"title\":\"日语角\"}"), CALLER).payload();

        entity.setLocation("图书馆");  // the student edited it on the schedule page meanwhile

        assertThatThrownBy(() -> update.apply(payload, USER))
                .isInstanceOf(ApiException.class).hasMessageContaining("被改过了");
        verify(scheduleService, never()).update(any(), anyLong(), any());
    }

    @Test
    void rollingBackAChangeRestoresTheOldValues() throws Exception {
        item(3, "日语角", "2026-11-21T14:00", "2026-11-21T15:00");
        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("itemId", 3);
        payload.set("before", json("{\"title\":\"英语角\",\"start\":\"2026-11-21T14:00\",\"end\":\"2026-11-21T15:00\","
                + "\"location\":null,\"notes\":null,\"status\":\"ACTIVE\"}"));
        JsonNode undo = json("{\"itemId\":3,\"after\":{\"title\":\"日语角\",\"start\":\"2026-11-21T14:00\","
                + "\"end\":\"2026-11-21T15:00\",\"location\":null,\"notes\":null,\"status\":\"ACTIVE\"}}");

        assertThat(update.undo(payload, undo, USER)).contains("改回");

        ArgumentCaptor<ScheduleDtos.UpdateScheduleItemRequest> request = ArgumentCaptor.forClass(ScheduleDtos.UpdateScheduleItemRequest.class);
        verify(scheduleService).update(eq(USER), eq(3L), request.capture());
        assertThat(request.getValue().title()).isEqualTo("英语角");
    }

    @Test
    void rollingBackAnAddedItemRefusesIfTheStudentEditedIt() throws Exception {
        item(8, "英语角（改过）", "2026-11-21T14:00", "2026-11-21T15:00");
        JsonNode undo = json("{\"itemId\":8,\"after\":{\"title\":\"英语角\",\"start\":\"2026-11-21T14:00\","
                + "\"end\":\"2026-11-21T15:00\",\"location\":null,\"notes\":null,\"status\":\"ACTIVE\"}}");

        assertThatThrownBy(() -> add.undo(MAPPER.createObjectNode(), undo, USER))
                .isInstanceOf(ApiException.class).hasMessageContaining("被改过了");
        verify(scheduleService, never()).cancel(any(), anyLong());
    }

    @Test
    void anAddCardWarnsAboutClashesButDoesNotBlock() throws Exception {
        when(scheduleService.list(USER, null)).thenReturn(List.of(new ScheduleDtos.ScheduleItemResponse(1L, "CUSTOM", null,
                "打工", LocalDateTime.parse("2099-11-21T13:00"), LocalDateTime.parse("2099-11-21T17:00"), null, null,
                "ACTIVE", null, null)));

        ConfirmableTool.Proposal proposal = add.propose(json("{\"title\":\"英语角\",\"start\":\"2099-11-21T14:00\","
                + "\"end\":\"2099-11-21T15:00\"}"), CALLER);

        assertThat(proposal.card().warnings()).singleElement().asString().contains("打工").contains("重叠");
        assertThatThrownBy(() -> add.propose(json("{\"title\":\"英语角\",\"start\":\"2099-11-21T15:00\","
                + "\"end\":\"2099-11-21T14:00\"}"), CALLER)).hasMessageContaining("结束时间要晚于开始时间");
    }

    @Test
    void reservationTimesMatchWithinAMillisecond() {
        Instant stored = Instant.parse("2026-11-20T03:00:00.123457Z");
        assertThat(ReserveEventTool.sameMoment(stored, "2026-11-20T03:00:00.123456789Z")).isTrue();
        assertThat(ReserveEventTool.sameMoment(stored, "2026-11-20T03:05:00Z")).isFalse();
    }
}
