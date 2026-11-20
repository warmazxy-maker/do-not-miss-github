package com.donotmiss.backend.master.wake;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Arrays;
import java.util.Collection;
import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;

/** The student's switches for proactive messages (F4 decision 5). No row means everything on. */
@Entity
@Table(name = "tandem_wake_settings")
public class WakeSettingsEntity {
    @Id
    @Column(name = "user_id", length = 80)
    private String userId;

    @Column(nullable = false)
    private boolean enabled;

    @Column(name = "disabled_kinds", nullable = false, length = 200)
    private String disabledKinds = "";

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public static WakeSettingsEntity defaults(String userId) {
        WakeSettingsEntity settings = new WakeSettingsEntity();
        settings.userId = userId;
        settings.enabled = true;
        settings.updatedAt = Instant.now();
        return settings;
    }

    public boolean allows(WakeKind kind) {
        return enabled && !disabled().contains(kind);
    }

    public Set<WakeKind> disabled() {
        if (disabledKinds == null || disabledKinds.isBlank()) {
            return EnumSet.noneOf(WakeKind.class);
        }
        Set<WakeKind> kinds = EnumSet.noneOf(WakeKind.class);
        Arrays.stream(disabledKinds.split(",")).map(String::trim).filter(name -> !name.isEmpty())
                .forEach(name -> {
                    try {
                        kinds.add(WakeKind.valueOf(name));
                    } catch (IllegalArgumentException ignored) {
                        // A kind that no longer exists.
                    }
                });
        return kinds;
    }

    public void update(boolean enabled, Collection<WakeKind> disabled) {
        this.enabled = enabled;
        this.disabledKinds = disabled.stream().map(Enum::name).sorted().collect(Collectors.joining(","));
        this.updatedAt = Instant.now();
    }

    public String getUserId() {
        return userId;
    }

    public boolean isEnabled() {
        return enabled;
    }
}
