package com.donotmiss.backend.master;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** Working memory of one student's conversation: the rolling summary of older messages (F1). */
@Entity
@Table(name = "tandem_conversations")
public class TandemConversationEntity {
    @Id
    @Column(name = "user_id", length = 80)
    private String userId;

    @Column(columnDefinition = "text")
    private String summary;

    /** Id of the last message folded into the summary; later messages are kept verbatim. */
    @Column(name = "summarized_up_to")
    private Long summarizedUpTo;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected TandemConversationEntity() {
    }

    public static TandemConversationEntity empty(String userId) {
        TandemConversationEntity conversation = new TandemConversationEntity();
        conversation.userId = userId;
        conversation.updatedAt = Instant.now();
        return conversation;
    }

    public void fold(String summary, Long summarizedUpTo) {
        this.summary = summary;
        this.summarizedUpTo = summarizedUpTo;
        this.updatedAt = Instant.now();
    }

    public String getUserId() {
        return userId;
    }

    public String getSummary() {
        return summary;
    }

    public Long getSummarizedUpTo() {
        return summarizedUpTo;
    }
}
