package com.donotmiss.backend.capabilityv2;

/**
 * One model proposal that Java validation refused, with the rule that refused it.
 *
 * <p>Validation used to discard proposals silently, which made "the model was wrong" and
 * "the model was right but a rule removed it" indistinguishable. Every drop is now kept in the
 * audit, the trace and the golden-set report.</p>
 */
public record CapabilityProposalDrop(
        int index,
        String action,
        Long bucketId,
        String bucketName,
        Reason reason
) {
    public enum Reason {
        EMPTY_ENTRY,
        INVALID_ACTION,
        MISSING_RELEVANCE,
        UNKNOWN_BUCKET_ID,
        INVALID_ROLE,
        DUPLICATE_PRIMARY,
        TOO_MANY_SECONDARY,
        DUPLICATE_BUCKET,
        OVER_LIMIT,
        NAME_TOO_LONG,
        NAME_TOO_SHORT,
        NAME_TOO_BROAD,
        NAME_TOO_FINE,
        NAME_TAKEN_BY_INACTIVE_BUCKET
    }
}
