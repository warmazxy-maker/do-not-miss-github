package com.donotmiss.backend.reservation;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ReservationRepository extends JpaRepository<ReservationEntity, Long> {
    List<ReservationEntity> findByUserIdAndStatusOrderByReservedAtDesc(String userId, ReservationStatus status);

    Optional<ReservationEntity> findByIdAndUserId(Long id, String userId);

    Optional<ReservationEntity> findByUserIdAndEventId(String userId, Long eventId);

    Optional<ReservationEntity> findByQrToken(String qrToken);
}
