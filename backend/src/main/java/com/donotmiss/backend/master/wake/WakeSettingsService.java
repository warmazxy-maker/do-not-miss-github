package com.donotmiss.backend.master.wake;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** F4: the student's switches (decision 5), and the counts the ops panel shows (decision 7). */
@Service
public class WakeSettingsService {
    private final WakeSettingsRepository settings;
    private final WakeTriggerRepository triggers;

    public WakeSettingsService(WakeSettingsRepository settings, WakeTriggerRepository triggers) {
        this.settings = settings;
        this.triggers = triggers;
    }

    public record Kind(String kind, String label, boolean enabled) {
    }

    public record Settings(boolean enabled, List<Kind> kinds) {
    }

    public record Update(boolean enabled, List<WakeKind> disabledKinds) {
    }

    @Transactional(readOnly = true)
    public Settings get(String userId) {
        return view(settings.findById(userId).orElseGet(() -> WakeSettingsEntity.defaults(userId)));
    }

    @Transactional
    public Settings update(String userId, Update update) {
        WakeSettingsEntity entity = settings.findById(userId).orElseGet(() -> WakeSettingsEntity.defaults(userId));
        Set<WakeKind> disabled = update.disabledKinds() == null || update.disabledKinds().isEmpty()
                ? EnumSet.noneOf(WakeKind.class) : EnumSet.copyOf(update.disabledKinds());
        entity.update(update.enabled(), disabled);
        return view(settings.save(entity));
    }

    private Settings view(WakeSettingsEntity entity) {
        Set<WakeKind> disabled = entity.disabled();
        return new Settings(entity.isEnabled(), Arrays.stream(WakeKind.values())
                .map(kind -> new Kind(kind.name(), kind.label(), !disabled.contains(kind)))
                .toList());
    }

    /** Sent, skipped and failed in the last 24 hours, and how many are waiting. */
    @Transactional(readOnly = true)
    public Map<String, Long> stats() {
        Instant since = Instant.now().minus(Duration.ofHours(24));
        Map<String, Long> counts = new LinkedHashMap<>();
        counts.put("tandemWakeSent24h", triggers.countByStatusAndProcessedAtAfter(WakeTriggerStatus.DONE, since));
        counts.put("tandemWakeSkipped24h", triggers.countByStatusAndProcessedAtAfter(WakeTriggerStatus.SKIPPED, since));
        counts.put("tandemWakeFailed24h", triggers.countByStatusAndProcessedAtAfter(WakeTriggerStatus.FAILED, since));
        counts.put("tandemWakePending", triggers.countByStatus(WakeTriggerStatus.PENDING));
        return counts;
    }
}
