package com.donotmiss.backend.reservation;

import com.donotmiss.backend.achievement.AchievementService;
import com.donotmiss.backend.common.ApiException;
import com.donotmiss.backend.event.EventEntity;
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
import java.util.List;
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

    public ReservationService(ReservationRepository reservationRepository,
                              EventService eventService,
                              AchievementService achievementService,
                              ScheduleService scheduleService,
                              StringRedisTemplate redisTemplate,
                              @Value("${app.qr.token-ttl-hours}") long ttlHours) {
        this.reservationRepository = reservationRepository;
        this.eventService = eventService;
        this.achievementService = achievementService;
        this.scheduleService = scheduleService;
        this.redisTemplate = redisTemplate;
        this.qrTokenTtl = Duration.ofHours(ttlHours);
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

        ReservationEntity reservation = existing == null ? new ReservationEntity() : existing;
        reservation.setUserId(userId);
        reservation.setEvent(event);
        reservation.setStatus(ReservationStatus.RESERVED);
        reservation.setQrToken(generateQrToken());
        reservation.setReservedAt(Instant.now());
        ReservationEntity saved = reservationRepository.save(reservation);
        cacheQrToken(saved);
        scheduleService.syncReservation(userId, saved);
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
    }

    @Transactional
    public ReservationDtos.ReservationResponse scanComplete(String userId, String qrToken) {
        Long reservationId = resolveReservationId(qrToken);
        ReservationEntity reservation = reservationRepository.findByIdAndUserId(reservationId, userId)
                .orElseThrow(() -> ApiException.forbidden("二维码不属于当前学生"));

        if (reservation.getStatus() != ReservationStatus.RESERVED) {
            throw ApiException.badRequest("这个预约不是可完成状态");
        }

        reservation.setStatus(ReservationStatus.COMPLETED);
        reservation.setCompletedAt(Instant.now());
        redisTemplate.delete(QR_PREFIX + reservation.getQrToken());

        achievementService.createIfAbsent(userId, reservation.getEvent());
        return ReservationDtos.ReservationResponse.from(reservation);
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
