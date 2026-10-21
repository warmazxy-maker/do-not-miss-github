package com.donotmiss.backend.resumeinterview;

import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(
        name = "resume_interview_resume_snapshots",
        indexes = {
                @Index(name = "idx_resume_snapshot_session_created", columnList = "session_id,created_at"),
                @Index(name = "idx_resume_snapshot_hash", columnList = "resume_hash")
        }
)
public class ResumeSnapshotEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "session_id", nullable = false)
    private Long sessionId;

    @Column(name = "resume_hash", nullable = false, length = 64, columnDefinition = "char(64)")
    private String resumeHash;

    @Column(name = "raw_resume_text", nullable = false, columnDefinition = "longtext")
    private String rawResumeText;

    @Column(name = "parsed_resume_json", nullable = false, columnDefinition = "json")
    private String parsedResumeJson = "{}";

    @Column(name = "parser_prompt_version", length = 80)
    private String parserPromptVersion;

    @Column(name = "parser_model_name", length = 120)
    private String parserModelName;

    @Column(name = "parser_trace_run_id")
    private Long parserTraceRunId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        if (parsedResumeJson == null) {
            parsedResumeJson = "{}";
        }
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    public Long getId() {
        return id;
    }

    public Long getSessionId() {
        return sessionId;
    }

    public void setSessionId(Long sessionId) {
        this.sessionId = sessionId;
    }

    public String getResumeHash() {
        return resumeHash;
    }

    public void setResumeHash(String resumeHash) {
        this.resumeHash = resumeHash;
    }

    public String getRawResumeText() {
        return rawResumeText;
    }

    public void setRawResumeText(String rawResumeText) {
        this.rawResumeText = rawResumeText;
    }

    public String getParsedResumeJson() {
        return parsedResumeJson;
    }

    public void setParsedResumeJson(String parsedResumeJson) {
        this.parsedResumeJson = parsedResumeJson;
    }

    public String getParserPromptVersion() {
        return parserPromptVersion;
    }

    public void setParserPromptVersion(String parserPromptVersion) {
        this.parserPromptVersion = parserPromptVersion;
    }

    public String getParserModelName() {
        return parserModelName;
    }

    public void setParserModelName(String parserModelName) {
        this.parserModelName = parserModelName;
    }

    public Long getParserTraceRunId() {
        return parserTraceRunId;
    }

    public void setParserTraceRunId(Long parserTraceRunId) {
        this.parserTraceRunId = parserTraceRunId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
