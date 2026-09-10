package ledger.core;

import java.math.BigDecimal;

/** A plain credit (money in). Always booked unconditionally — see AMBIGUITIES.md
 *  on why plain CREDIT/DEBIT are never gated by an available-balance check
 *  (only authorizations are, per the brief's non-negotiable rule). */
public record CreditEvent(String id, String accountId, int postedDay, int valueDate, BigDecimal amount)
        implements Event {
}
