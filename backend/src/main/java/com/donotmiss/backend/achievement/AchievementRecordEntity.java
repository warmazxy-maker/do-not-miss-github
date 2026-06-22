package com.donotmiss.backend.achievement;

import com.donotmiss.backend.challenge.ChallengeEntity;
import com.donotmiss.backend.event.BenefitType;
import com.donotmiss.backend.event.EventEntity;
import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

@Entity
@Table(name = "achievement_records")
public class AchievementRecordEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, length = 80)
    private String userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private AchievementSourceType sourceType;

    @Column(nullable = false)
    private Long sourceId;

    @Column(name = "event_id", nullable = false)
    private Long eventId;

    @Column(nullable = false, length = 120)
    private String eventTitle;

    @Column(nullable = false, length = 120)
    private String organizationName;

    @Column(nullable = false, length = 60)
    private String category;

    @Column(nullable = false)
    private LocalDateTime eventStartTime;

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

    @Column(nullable = false)
    private Instant completedAt;

    @Column(length = 2000)
    private String did;

    @Column(length = 2000)
    private String learned;

    public static AchievementRecordEntity fromCompletedEvent(String userId, EventEntity event) {
        AchievementRecordEntity record = new AchievementRecordEntity();
        record.setUserId(userId);
        record.setSourceType(AchievementSourceType.EVENT);
        record.setSourceId(event.getId());
        record.setEventId(event.getId());
        record.setEventTitle(event.getTitle());
        record.setOrganizationName(event.getOrganizationName());
        record.setCategory(event.getCategory().label());
        record.setEventStartTime(event.getStartTime());
        record.setLocation(event.getLocation());
        record.setContent(event.getContent());
        record.setBenefitType(event.getBenefitType());
        record.setSkill(event.getSkill());
        record.setMoneyAmount(event.getMoneyAmount());
        record.setCompletedAt(Instant.now());
        return record;
    }

    public static AchievementRecordEntity fromCompletedChallenge(String userId, ChallengeEntity challenge) {
        AchievementRecordEntity record = new AchievementRecordEntity();
        record.setUserId(userId);
        record.setSourceType(AchievementSourceType.CHALLENGE);
        record.setSourceId(challenge.getId());
        record.setEventId(challenge.getId());
        record.setEventTitle(challenge.getTitle());
        record.setOrganizationName("个人挑战");
        record.setCategory(challenge.getCategory());
        record.setEventStartTime(LocalDateTime.ofInstant(challenge.getCreatedAt(), ZoneId.systemDefault()));
        record.setLocation("自定义");
        record.setContent(challenge.getDescription());
        record.setBenefitType(BenefitType.SKILL);
        record.setSkill(challenge.getGoal());
        record.setMoneyAmount(null);
        record.setCompletedAt(challenge.getCompletedAt() == null ? Instant.now() : challenge.getCompletedAt());
        record.setDid(challenge.getDid());
        record.setLearned(challenge.getLearned());
        return record;
    }

    public Long getId() {
        return id;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public AchievementSourceType getSourceType() {
        return sourceType;
    }

    public void setSourceType(AchievementSourceType sourceType) {
        this.sourceType = sourceType;
    }

    public Long getSourceId() {
        return sourceId;
    }

    public void setSourceId(Long sourceId) {
        this.sourceId = sourceId;
    }

    public Long getEventId() {
        return eventId;
    }

    public void setEventId(Long eventId) {
        this.eventId = eventId;
    }

    public String getEventTitle() {
        return eventTitle;
    }

    public void setEventTitle(String eventTitle) {
        this.eventTitle = eventTitle;
    }

    public String getOrganizationName() {
        return organizationName;
    }

    public void setOrganizationName(String organizationName) {
        this.organizationName = organizationName;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public LocalDateTime getEventStartTime() {
        return eventStartTime;
    }

    public void setEventStartTime(LocalDateTime eventStartTime) {
        this.eventStartTime = eventStartTime;
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

    public Instant getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(Instant completedAt) {
        this.completedAt = completedAt;
    }

    public String getDid() {
        return did;
    }

    public void setDid(String did) {
        this.did = did;
    }

    public String getLearned() {
        return learned;
    }

    public void setLearned(String learned) {
        this.learned = learned;
    }
}
