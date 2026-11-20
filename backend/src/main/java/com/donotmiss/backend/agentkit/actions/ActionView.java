package com.donotmiss.backend.agentkit.actions;

import java.time.Instant;

/**
 * A confirmation card as the student sees it.
 *
 * @param status     the {@link PendingActionStatus} name; an overdue PENDING card shows as EXPIRED
 * @param message    what happened when it ran, failed or was rolled back
 * @param canConfirm the confirm and cancel buttons are live
 * @param canUndo    the rollback button is live (it may still refuse if the result was changed since)
 */
public record ActionView(Long id, String tool, ActionCard card, String status, String statusLabel,
                         String message, Instant expiresAt, boolean canConfirm, boolean canUndo) {
}
