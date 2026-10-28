package com.donotmiss.backend.reservation;

import com.donotmiss.backend.recommendation.RecommendationActivityEvent;
import com.donotmiss.backend.recommendation.RecommendationFeedbackType;
import org.springframework.context.ApplicationEventPublisher;
import com.donotmiss.backend.achievement.AchievementService;
import com.donotmiss.backend.common.ApiException;
import com.donotmiss.backend.event.EventDtos;
import com.donotmiss.backend.event.EventEntity;
import com.donotmiss.backend.event.EventReviewStatus;
import com.donotmiss.backend.event.EventService;
import com.donotmiss.backend.schedule.ScheduleItemType;
import com.donotmiss.backend.schedule.ScheduleService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Service
public class ReservationService {
    private static final String QR_PREFIX = "reservation:qr:";

    private final ReservationRepository reservationRepository;
    private final EventService eventService;
    private final AchievementService achievementService;
    private final ScheduleService scheduleService;
    private final StringRedisTemplate redisTemplate;
    private final Duration qrTokenTtl;
    private final ApplicationEventPublisher eventPublisher;

    public ReservationService(ReservationRepository reservationRepository,
                              EventService eventService,
                              AchievementService achievementService,
                              ScheduleService scheduleService,
                              StringRedisTemplate redisTemplate,
                              @Value("${app.qr.token-ttl-hours}") long ttlHours,
                              ApplicationEventPublisher eventPublisher) {
        this.reservationRepository = reservationRepository;
        this.eventService = eventService;
        this.achievementService = achievementService;
        this.scheduleService = scheduleService;
        this.redisTemplate = redisTemplate;
        this.qrTokenTtl = Duration.ofHours(ttlHours);
        this.eventPublisher = eventPublisher;
    }

    @Transactional
    public ReservationDtos.ReservationResponse reserve(String userId, Long eventId) {
        EventEntity event = eventService.getEntity(eventId);
        ReservationEntity existing = reservationRepository.findByUserIdAndEventId(userId, eventId).orElse(null);

        if (existing != null && existing.getStatus() == ReservationStatus.COMPLETED) {
            throw ApiException.badRequest("这个活动已经完成，不能重复预约");
        }

        if (existing != null && existing.getStatus() == ReservationStatus.RESERVED) {
            refreshQrToken(existing);
            scheduleService.syncReservation(userId, existing);
            return ReservationDtos.ReservationResponse.from(existing);
        }

        // Before D2 any event id could be reserved, including drafts, events under review and
        // events that had already ended.
        if (event.getReviewStatus() != EventReviewStatus.APPROVED || event.hasEnded(LocalDateTime.now())) {
            throw ApiException.badRequest("活动未上架或已结束，不能预约");
        }

        ReservationEntity reservation = existing == null ? new ReservationEntity() : existing;
        reservation.setUserId(userId);
        reservation.setEvent(event);
        reservation.setStatus(ReservationStatus.RESERVED);
        reservation.setQrToken(generateQrToken());
        reservation.setReservedAt(Instant.now());
        ReservationEntity saved = reservationRepository.save(reservation);
        cacheQrToken(saved);
        scheduleService.syncReservation(userId, saved);
        eventPublisher.publishEvent(RecommendationActivityEvent.forEvent(userId, eventId, RecommendationFeedbackType.RESERVED));
        return ReservationDtos.ReservationResponse.from(saved);
    }

    @Transactional(readOnly = true)
    public List<ReservationDtos.ReservationResponse> listActive(String userId) {
        return reservationRepository.findByUserIdAndStatusOrderByReservedAtDesc(userId, ReservationStatus.RESERVED).stream()
                .map(ReservationDtos.ReservationResponse::from)
                .toList();
    }

    @Transactional
    public void cancel(String userId, Long reservationId) {
        ReservationEntity reservation = reservationRepository.findByIdAndUserId(reservationId, userId)
                .orElseThrow(() -> ApiException.notFound("预约不存在：" + reservationId));
        if (reservation.getStatus() != ReservationStatus.RESERVED) {
            throw ApiException.badRequest("只有预约中状态可以取消");
        }
        reservation.setStatus(ReservationStatus.CANCELLED);
        redisTemplate.delete(QR_PREFIX + reservation.getQrToken());
        scheduleService.cancelSource(userId, ScheduleItemType.RESERVATION, reservation.getId());
        eventPublisher.publishEvent(RecommendationActivityEvent.forEvent(
                userId, reservation.getEvent().getId(), RecommendationFeedbackType.RESERVATION_CANCELLED));
    }

    /**
     * Check-in at the venue: the organisation scans the student's QR code (D2, L7). Before D2 the
     * student called this on their own code, so "completed" proved nothing. Allowed while the event
     * is published and right after it ended (the expiry job may already have taken it offline).
     */
    @Transactional
    public ReservationDtos.CheckInResponse checkIn(Long eventId, String organizerUserId, String qrToken) {
        EventEntity event = eventService.getEntity(eventId);
        if (!Objects.equals(event.getCreatedByUserId(), organizerUserId)) {
            throw ApiException.forbidden("只能为自己发布的活动签到");
        }
        if (!EventDtos.OrganizerEventResponse.canCheckIn(event)) {
            throw ApiException.badRequest("活动当前不能签到（未上架或已下架）");
        }
        Long reservationId = resolveReservationId(qrToken.trim());
        ReservationEntity reservation = reservationRepository.findById(reservationId)
                .orElseThrow(() -> ApiException.badRequest("二维码无效或已过期"));
        if (!Objects.equals(reservation.getEvent().getId(), eventId)) {
            throw ApiException.badRequest("这个二维码不是本活动的预约");
        }
        if (reservation.getStatus() != ReservationStatus.RESERVED) {
            throw ApiException.badRequest("这个预约不是可签到状态（已签到或已取消）");
        }

        String userId = reservation.getUserId();
        reservation.setStatus(ReservationStatus.COMPLETED);
        reservation.setCompletedAt(Instant.now());
        redisTemplate.delete(QR_PREFIX + reservation.getQrToken());

        achievementService.createIfAbsent(userId, reservation.getEvent());
        eventPublisher.publishEvent(RecommendationActivityEvent.forEvent(
                userId, reservation.getEvent().getId(), RecommendationFeedbackType.COMPLETED));
        return new ReservationDtos.CheckInResponse(reservation.getId(), userId, reservation.getCompletedAt());
    }

    private Long resolveReservationId(String qrToken) {
        String cached = redisTemplate.opsForValue().get(QR_PREFIX + qrToken);
        if (cached != null) {
            return Long.valueOf(cached);
        }

        // Redis 过期后，数据库仍能兜底查到 token；生产环境可以按安全要求禁止兜底。
        return reservationRepository.findByQrToken(qrToken)
                .map(ReservationEntity::getId)
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "二维码无效或已过期"));
    }

    private void refreshQrToken(ReservationEntity reservation) {
        if (reservation.getQrToken() == null || reservation.getQrToken().isBlank()) {
            reservation.setQrToken(generateQrToken());
        }
        cacheQrToken(reservation);
    }

    private void cacheQrToken(ReservationEntity reservation) {
        redisTemplate.opsForValue().set(QR_PREFIX + reservation.getQrToken(), reservation.getId().toString(), qrTokenTtl);
    }

    private String generateQrToken() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
