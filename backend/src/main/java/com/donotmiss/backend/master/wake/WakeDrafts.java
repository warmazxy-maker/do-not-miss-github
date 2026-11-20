package com.donotmiss.backend.master.wake;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What a wake run decided to say, held by run id until the processor saves it. The tool only
 * drafts; the processor is the single writer, so the message, its hidden note and the triggers
 * it answers are saved together.
 */
@Component
public class WakeDrafts {
    private final Map<Long, String> drafts = new ConcurrentHashMap<>();

    /** False when this run already left one: one message per wake. */
    public boolean put(Long runId, String text) {
        return runId != null && drafts.putIfAbsent(runId, text) == null;
    }

    public String take(Long runId) {
        return runId == null ? null : drafts.remove(runId);
    }
}
