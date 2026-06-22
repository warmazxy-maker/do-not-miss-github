package com.donotmiss.backend.retrieval;

import com.donotmiss.backend.event.EventEntity;

import java.util.List;

public record RetrievedEvent(
        EventEntity event,
        double keywordScore,
        double bm25Score,
        double semanticScore,
        double intentScore,
        double memoryScore,
        double metadataScore,
        double recallScore,
        double finalScore,
        List<String> evidence
) {
}
