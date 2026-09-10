package ledger.core;

import java.math.BigDecimal;

/**
 * Settlement of a previously authorized hold, referencing {@code authId}.
 * Rejected (with an {@link ErrorRecord}, no funds move) if {@code authId} does
 * not correspond to a known, still-active authorization on this account.
 *
 * NOTE ON A KNOWN GAP: this record does not distinguish "settle amount <= hold
 * amount" from "settle amount > hold amount". The current engine books whatever
 * amount is given, as long as the authId is known and active. Whether an
 * over-settlement (settling for MORE than was held) should instead be rejected,
 * or capped at the hold amount, is not specified by the brief and is left as an
 * intentional, documented gap — see the failing test
 * {@code OverSettlementExceedsHoldTest} and REJECTED.md ("approaches abandoned").
 */
public record SettlementEvent(
        String id, String accountId, int postedDay, int valueDate, String authId, BigDecimal settleAmount)
        implements Event {
}
