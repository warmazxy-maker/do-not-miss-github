package com.donotmiss.backend.schedule;

import com.donotmiss.backend.challenge.ChallengeEntity;
import com.donotmiss.backend.challenge.ChallengeRepository;
import com.donotmiss.backend.common.ApiException;
import com.donotmiss.backend.event.EventEntity;
import com.donotmiss.backend.event.EventService;
import com.donotmiss.backend.reservation.ReservationEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class ScheduleService {
    private static final int MAX_IMPORT_STEPS = 12;
    private static final Pattern WEEK_PATTERN = Pattern.compile("第\\s*(\\d+)\\s*周");

    private final ScheduleItemRepository scheduleItemRepository;
    private final ChallengeRepository challengeRepository;
    private final EventService eventService;

    public ScheduleService(ScheduleItemRepository scheduleItemRepository,
                           ChallengeRepository challengeRepository,
                           EventService eventService) {
        this.scheduleItemRepository = scheduleItemRepository;
        this.challengeRepository = challengeRepository;
        this.eventService = eventService;
    }

    @Transactional(readOnly = true)
    public List<ScheduleDtos.ScheduleItemResponse> list(String userId, String month) {
        List<ScheduleItemEntity> items;

        if (month == null || month.isBlank()) {
            items = scheduleItemRepository.findByUserIdAndStatusOrderByStartTimeAsc(userId, ScheduleItemStatus.ACTIVE);
        } else {
            YearMonth yearMonth = YearMonth.parse(month);
            LocalDateTime from = yearMonth.atDay(1).atStartOfDay();
            LocalDateTime to = yearMonth.plusMonths(1).atDay(1).atStartOfDay();
            items = scheduleItemRepository.findByUserIdAndStatusAndStartTimeBetweenOrderByStartTimeAsc(
                    userId,
                    ScheduleItemStatus.ACTIVE,
                    from,
                    to
            );
        }

        return items.stream().map(ScheduleDtos.ScheduleItemResponse::from).toList();
    }

    @Transactional
    public ScheduleDtos.ScheduleItemResponse create(String userId, ScheduleDtos.CreateScheduleItemRequest request) {
        ScheduleItemType type = ScheduleItemType.fromText(request.itemType());
        validateTimeRange(request.startTime(), request.endTime());

        if (type == ScheduleItemType.RESERVATION) {
            throw ApiException.badRequest("Reservation schedule items are created automatically.");
        }

        if (type == ScheduleItemType.CHALLENGE && request.sourceId() != null) {
            challengeRepository.findByIdAndUserId(request.sourceId(), userId)
                    .orElseThrow(() -> ApiException.notFound("Challenge not found: " + request.sourceId()));
        }

        ScheduleItemEntity item = new ScheduleItemEntity();
        item.setUserId(userId);
        item.setItemType(type);
        item.setSourceId(type == ScheduleItemType.CUSTOM ? null : request.sourceId());
        applyEditableFields(item, request.title(), request.startTime(), request.endTime(), request.location(), request.notes());

        return ScheduleDtos.ScheduleItemResponse.from(scheduleItemRepository.save(item));
    }

    @Transactional
    public ScheduleDtos.ImportAiPlanResponse importAiPlan(String userId, ScheduleDtos.ImportAiPlanRequest request) {
        if (request.steps() == null || request.steps().isEmpty()) {
            throw ApiException.badRequest("Plan steps cannot be empty.");
        }

        List<ScheduleDtos.ScheduleItemResponse> importedItems = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        int skippedCount = 0;
        LocalDate baseDate = LocalDate.now().plusDays(1);
        List<ScheduleDtos.AiPlanStepRequest> steps = request.steps().stream()
                .filter(step -> step != null && step.title() != null && !step.title().isBlank())
                .sorted(Comparator.comparingInt(step -> step.order() <= 0 ? Integer.MAX_VALUE : step.order()))
                .limit(MAX_IMPORT_STEPS)
                .toList();

        int fallbackIndex = 0;
        for (ScheduleDtos.AiPlanStepRequest step : steps) {
            Optional<EventEntity> event = findEventForPlanStep(step, warnings);
            if (isEventStep(step) && step.eventId() != null && event.isEmpty()) {
                skippedCount += 1;
                continue;
            }

            ScheduleItemEntity item = event
                    .flatMap(found -> scheduleItemRepository.findByUserIdAndItemTypeAndSourceId(userId, ScheduleItemType.AI_PLAN, found.getId()))
                    .orElseGet(ScheduleItemEntity::new);

            item.setUserId(userId);
            item.setItemType(ScheduleItemType.AI_PLAN);
            item.setSourceId(event.map(EventEntity::getId).orElse(null));
            item.setStatus(ScheduleItemStatus.ACTIVE);

            int currentFallbackIndex = fallbackIndex;
            LocalDateTime startTime = event.map(EventEntity::getStartTime)
                    .orElseGet(() -> inferredStartTime(baseDate, step, currentFallbackIndex));
            LocalDateTime endTime = event.map(EventEntity::getEndTime)
                    .orElseGet(() -> startTime.plusMinutes(durationMinutes(step)));

            item.setTitle(compact(stepTitle(step, event), 160));
            item.setStartTime(startTime);
            item.setEndTime(endTime);
            item.setLocation(compact(event.map(EventEntity::getLocation).orElse(defaultLocation(step)), 160));
            item.setNotes(compact(planNotes(request, step, event), 1000));
            importedItems.add(ScheduleDtos.ScheduleItemResponse.from(scheduleItemRepository.save(item)));

            if (event.isEmpty()) {
                fallbackIndex += 1;
            }
        }

        if (request.steps().size() > MAX_IMPORT_STEPS) {
            warnings.add("计划步骤超过 " + MAX_IMPORT_STEPS + " 个，已只导入前 " + MAX_IMPORT_STEPS + " 个。");
        }
        return new ScheduleDtos.ImportAiPlanResponse(importedItems.size(), skippedCount, warnings, importedItems);
    }

    @Transactional
    public ScheduleDtos.ScheduleItemResponse update(String userId, Long itemId, ScheduleDtos.UpdateScheduleItemRequest request) {
        validateTimeRange(request.startTime(), request.endTime());
        ScheduleItemEntity item = scheduleItemRepository.findByIdAndUserId(itemId, userId)
                .orElseThrow(() -> ApiException.notFound("Schedule item not found: " + itemId));

        if (item.getStatus() != ScheduleItemStatus.ACTIVE) {
            throw ApiException.badRequest("Only active schedule items can be edited.");
        }

        applyEditableFields(item, request.title(), request.startTime(), request.endTime(), request.location(), request.notes());
        return ScheduleDtos.ScheduleItemResponse.from(item);
    }

    @Transactional
    public void cancel(String userId, Long itemId) {
        ScheduleItemEntity item = scheduleItemRepository.findByIdAndUserId(itemId, userId)
                .orElseThrow(() -> ApiException.notFound("Schedule item not found: " + itemId));
        item.setStatus(ScheduleItemStatus.CANCELLED);
    }

    @Transactional
    public void syncReservation(String userId, ReservationEntity reservation) {
        EventEntity event = reservation.getEvent();
        ScheduleItemEntity item = scheduleItemRepository
                .findByUserIdAndItemTypeAndSourceId(userId, ScheduleItemType.RESERVATION, reservation.getId())
                .orElseGet(ScheduleItemEntity::new);

        item.setUserId(userId);
        item.setItemType(ScheduleItemType.RESERVATION);
        item.setSourceId(reservation.getId());
        item.setStatus(ScheduleItemStatus.ACTIVE);
        item.setTitle(event.getTitle());
        item.setStartTime(event.getStartTime());
        item.setEndTime(event.getEndTime());
        item.setLocation(event.getLocation());
        item.setNotes(event.getContent());
        scheduleItemRepository.save(item);
    }

    @Transactional
    public void cancelSource(String userId, ScheduleItemType type, Long sourceId) {
        if (sourceId == null) {
            return;
        }

        scheduleItemRepository.findByUserIdAndItemTypeAndSourceIdOrderByStartTimeAsc(userId, type, sourceId)
                .forEach(item -> item.setStatus(ScheduleItemStatus.CANCELLED));
    }

    @Transactional
    public void syncChallengeTitle(String userId, ChallengeEntity challenge) {
        scheduleItemRepository.findByUserIdAndItemTypeAndSourceIdOrderByStartTimeAsc(
                        userId,
                        ScheduleItemType.CHALLENGE,
                        challenge.getId()
                )
                .forEach(item -> {
                    if (item.getTitle() == null || item.getTitle().isBlank()) {
                        item.setTitle(challenge.getTitle());
                    }
                });
    }

    private Optional<EventEntity> findEventForPlanStep(ScheduleDtos.AiPlanStepRequest step, List<String> warnings) {
        if (!isEventStep(step) || step.eventId() == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(eventService.getEntity(step.eventId()));
        } catch (RuntimeException ex) {
            warnings.add("活动 #" + step.eventId() + " 不存在，已跳过步骤：" + step.title());
            return Optional.empty();
        }
    }

    private boolean isEventStep(ScheduleDtos.AiPlanStepRequest step) {
        return step.itemType() != null && "EVENT".equalsIgnoreCase(step.itemType().trim());
    }

    private LocalDateTime inferredStartTime(LocalDate baseDate, ScheduleDtos.AiPlanStepRequest step, int fallbackIndex) {
        int weekOffset = Math.max(extractWeekIndex(step.dateLabel(), step.scheduleHint()) - 1, 0);
        int dayOffset = weekOffset * 7 + fallbackIndex % 5;
        LocalTime time = switch (normalizedItemType(step)) {
            case "REFLECTION" -> LocalTime.of(21, 0);
            case "CHALLENGE" -> LocalTime.of(18, 30);
            default -> LocalTime.of(19, 0);
        };
        return baseDate.plusDays(dayOffset).atTime(time);
    }

    private int extractWeekIndex(String dateLabel, String scheduleHint) {
        String text = (blankToEmpty(dateLabel) + " " + blankToEmpty(scheduleHint)).trim();
        Matcher matcher = WEEK_PATTERN.matcher(text);
        if (matcher.find()) {
            try {
                return Math.max(Integer.parseInt(matcher.group(1)), 1);
            } catch (NumberFormatException ignored) {
                return 1;
            }
        }
        return 1;
    }

    private long durationMinutes(ScheduleDtos.AiPlanStepRequest step) {
        return switch (normalizedItemType(step)) {
            case "REFLECTION" -> 45;
            case "CHALLENGE" -> 90;
            default -> 75;
        };
    }

    private String normalizedItemType(ScheduleDtos.AiPlanStepRequest step) {
        return step.itemType() == null || step.itemType().isBlank()
                ? "STUDY"
                : step.itemType().trim().toUpperCase();
    }

    private String stepTitle(ScheduleDtos.AiPlanStepRequest step, Optional<EventEntity> event) {
        if (event.isPresent()) {
            return "AI计划：" + event.get().getTitle();
        }
        return "AI计划：" + step.title().trim();
    }

    private String defaultLocation(ScheduleDtos.AiPlanStepRequest step) {
        String hint = blankToEmpty(step.scheduleHint());
        if (hint.contains("线上") || hint.toLowerCase().contains("online")) {
            return "线上";
        }
        return null;
    }

    private String planNotes(ScheduleDtos.ImportAiPlanRequest request,
                             ScheduleDtos.AiPlanStepRequest step,
                             Optional<EventEntity> event) {
        List<String> parts = new ArrayList<>();
        parts.add("来自 AI 推荐计划：" + request.title());
        parts.add("目标：" + request.goal());
        if (request.style() != null && !request.style().isBlank()) {
            parts.add("风格：" + request.style());
        }
        parts.add("步骤：" + step.title());
        if (step.dateLabel() != null && !step.dateLabel().isBlank()) {
            parts.add("计划时间：" + step.dateLabel());
        }
        if (step.scheduleHint() != null && !step.scheduleHint().isBlank()) {
            parts.add("安排建议：" + step.scheduleHint());
        }
        if (step.reason() != null && !step.reason().isBlank()) {
            parts.add("理由：" + step.reason());
        }
        event.ifPresent(value -> parts.add("关联活动ID：" + value.getId()));
        return String.join("\n", parts);
    }

    private String compact(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        String text = value.trim();
        return text.length() > maxLength ? text.substring(0, maxLength) : text;
    }

    private String blankToEmpty(String value) {
        return value == null ? "" : value;
    }

    private void applyEditableFields(ScheduleItemEntity item,
                                     String title,
                                     LocalDateTime startTime,
                                     LocalDateTime endTime,
                                     String location,
                                     String notes) {
        item.setTitle(title.trim());
        item.setStartTime(startTime);
        item.setEndTime(endTime);
        item.setLocation(blankToNull(location));
        item.setNotes(blankToNull(notes));
    }

    private void validateTimeRange(LocalDateTime startTime, LocalDateTime endTime) {
        if (!endTime.isAfter(startTime)) {
            throw ApiException.badRequest("End time must be after start time.");
        }
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
