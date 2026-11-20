package com.donotmiss.backend.master;

import com.donotmiss.backend.agentkit.actions.ActionView;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public final class TandemDtos {
    private TandemDtos() {
    }

    public record ChatRequest(@NotBlank @Size(max = 2000) String message) {
    }

    /** A tool step as the student sees it: what was looked up, never the raw data. */
    public record Step(String tool, String title, String detail, String status, String summary) {
    }

    /**
     * {@code actions}: F2 confirmation cards proposed in this answer (assistant messages only).
     * {@code proactive}: F4, Tandem wrote it on its own; {@code unread}: not seen yet.
     */
    public record Message(Long id, String role, String content, List<Step> steps, List<String> notices,
                          boolean degraded, Instant createdAt, List<ActionView> actions, boolean proactive,
                          boolean unread) {
    }

    public record MessagePage(List<Message> messages, boolean hasMore) {
    }

    public record Memory(Long id, String category, String categoryLabel, String content, String quote,
                         LocalDate validUntil, boolean expired, Instant updatedAt) {
    }

    public record Memories(List<Memory> memories, int limit) {
    }

    /** Live progress pushed over SSE while a turn runs. */
    public record Progress(String key, String kind, String title, String detail, String status, String summary) {
    }
}
