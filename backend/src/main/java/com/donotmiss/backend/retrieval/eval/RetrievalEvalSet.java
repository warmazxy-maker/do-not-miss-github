package com.donotmiss.backend.retrieval.eval;

import com.donotmiss.backend.retrieval.RetrievalDtos;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * A versioned evaluation set. {@code version} changes whenever cases or labels change;
 * {@code corpusVersion} names the frozen events the labels refer to; {@code asOf} is the clock
 * every case runs at, so time-sensitive scoring (expiry, "upcoming") is reproducible.
 * Only results with the same version are comparable.
 */
public record RetrievalEvalSet(
        String version,
        String corpusVersion,
        OffsetDateTime asOf,
        String labelStatus,
        List<RetrievalDtos.EvalCase> cases
) {
}
