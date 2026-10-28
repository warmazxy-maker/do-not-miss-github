package com.donotmiss.backend.event;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;

@Entity
@Table(name = "events")
public class EventEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 120)
    private String title;

    @Column(nullable = false, length = 120)
    private String organizationName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private EventCategory category;

    @Column(nullable = false)
    private LocalDateTime startTime;

    @Column(nullable = false)
    private LocalDateTime endTime;

    /**
     * Publication dimension. Changed only by EventLifecycleService; the time dimension
     * (not started / ongoing / ended) is never stored, see {@link EventTimePhase}.
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private EventReviewStatus reviewStatus = EventReviewStatus.DRAFT;

    @Enumerated(EnumType.STRING)
    @Column(length = 24)
    private EventOfflineReason offlineReasonCode;

    @Column(length = 300)
    private String offlineReason;

    @Column(length = 500)
    private String revisionNote;

    @Column(nullable = false)
    private int aiRevisionCount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private EventReviewRoute reviewRoute = EventReviewRoute.AI;

    /** Optimistic lock: two concurrent decisions on one event cannot both win. */
    @Version
    @Column(nullable = false)
    private long version;

    @Column(nullable = false, length = 160)
    private String location;

    @Column(nullable = false, length = 2000)
    private String content;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private BenefitType benefitType;

    @Column(length = 500)
    private String skill;

    @Column(precision = 12, scale = 2)
    private BigDecimal moneyAmount;

    @Column(nullable = false, length = 80)
    private String createdByUserId;

    @Column(nullable = false)
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    /**
     * An event that only lives in memory, with a fixed id, for frozen evaluation corpora.
     * It must never be saved: the id would collide with a real row. A frozen corpus only holds
     * published events, so it starts APPROVED without going through the lifecycle.
     */
    public static EventEntity detached(Long id) {
        EventEntity event = new EventEntity();
        event.id = id;
        event.reviewStatus = EventReviewStatus.APPROVED;
        return event;
    }

    public Long getId() {
        return id;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getOrganizationName() {
        return organizationName;
    }

    public void setOrganizationName(String organizationName) {
        this.organizationName = organizationName;
    }

    public EventCategory getCategory() {
        return category;
    }

    public void setCategory(EventCategory category) {
        this.category = category;
    }

    public LocalDateTime getStartTime() {
        return startTime;
    }

    public void setStartTime(LocalDateTime startTime) {
        this.startTime = startTime;
    }

    public LocalDateTime getEndTime() {
        return endTime;
    }

    public void setEndTime(LocalDateTime endTime) {
        this.endTime = endTime;
    }

    public boolean hasEnded(LocalDateTime now) {
        return endTime != null && !endTime.isAfter(now);
    }

    public EventTimePhase timePhase(LocalDateTime now) {
        return EventTimePhase.of(this, now);
    }

    public EventReviewStatus getReviewStatus() {
        return reviewStatus;
    }

    /** Package-private: only the lifecycle may move an event between states. */
    void setReviewStatus(EventReviewStatus reviewStatus) {
        this.reviewStatus = reviewStatus;
    }

    public String getLocation() {
        return location;
    }

    public void setLocation(String location) {
        this.location = location;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public BenefitType getBenefitType() {
        return benefitType;
    }

    public void setBenefitType(BenefitType benefitType) {
        this.benefitType = benefitType;
    }

    public String getSkill() {
        return skill;
    }

    public void setSkill(String skill) {
        this.skill = skill;
    }

    public BigDecimal getMoneyAmount() {
        return moneyAmount;
    }

    public void setMoneyAmount(BigDecimal moneyAmount) {
        this.moneyAmount = moneyAmount;
    }

    public String getCreatedByUserId() {
        return createdByUserId;
    }

    public void setCreatedByUserId(String createdByUserId) {
        this.createdByUserId = createdByUserId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public EventOfflineReason getOfflineReasonCode() {
        return offlineReasonCode;
    }

    void setOfflineReasonCode(EventOfflineReason offlineReasonCode) {
        this.offlineReasonCode = offlineReasonCode;
    }

    public String getOfflineReason() {
        return offlineReason;
    }

    void setOfflineReason(String offlineReason) {
        this.offlineReason = offlineReason;
    }

    public String getRevisionNote() {
        return revisionNote;
    }

    void setRevisionNote(String revisionNote) {
        this.revisionNote = revisionNote;
    }

    public int getAiRevisionCount() {
        return aiRevisionCount;
    }

    void setAiRevisionCount(int aiRevisionCount) {
        this.aiRevisionCount = aiRevisionCount;
    }

    public EventReviewRoute getReviewRoute() {
        return reviewRoute;
    }

    void setReviewRoute(EventReviewRoute reviewRoute) {
        this.reviewRoute = reviewRoute;
    }

    public long getVersion() {
        return version;
    }
}
