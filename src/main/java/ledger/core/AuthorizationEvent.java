package ledger.core;

import java.math.BigDecimal;

/**
 * A request to place a hold of {@code holdAmount} against {@code authId}.
 * Approval/decline is decided at processing time against the non-negotiable
 * rule: approved only if (ledger balance - active holds - holdAmount) >= 0.
 * The outcome is recorded as an immutable {@link HoldEvent}; nothing here is
 * ever retried or re-decided later.
 */
public record AuthorizationEvent(
        String id, String accountId, int postedDay, int valueDate, String authId, BigDecimal holdAmount)
        implements Event {
}
