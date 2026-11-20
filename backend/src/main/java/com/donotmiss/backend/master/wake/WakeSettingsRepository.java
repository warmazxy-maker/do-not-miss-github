package com.donotmiss.backend.master.wake;

import org.springframework.data.jpa.repository.JpaRepository;

public interface WakeSettingsRepository extends JpaRepository<WakeSettingsEntity, String> {
}
