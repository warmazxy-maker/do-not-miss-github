package com.donotmiss.backend.retrieval.eval;

import com.fasterxml.jackson.databind.ObjectMapper;

/** Exposes the package-private fixture parsers to tests in other packages. */
public final class RetrievalEvalTestAccess {
    private RetrievalEvalTestAccess() {
    }

    public static RetrievalEvalSet readEvalSet(ObjectMapper objectMapper, byte[] bytes) {
        return RetrievalEvalFixtures.readEvalSet(objectMapper, bytes);
    }

    /** A corpus without a search index: retrieval falls back to the in-memory filter. */
    public static FrozenEventCorpus readCorpus(ObjectMapper objectMapper, byte[] bytes, String embeddingLabel) {
        return RetrievalEvalFixtures.readCorpus(objectMapper, bytes, embeddingLabel, null);
    }
}
