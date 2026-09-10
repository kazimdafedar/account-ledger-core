package ledger.core;

import java.math.BigDecimal;

/** A plain debit (money out). {@code amount} is positive; the engine negates it
 *  when booking. Always booked unconditionally, even if it drives the account
 *  negative — that's precisely what the overdraft fee rule exists to handle. */
public record DebitEvent(String id, String accountId, int postedDay, int valueDate, BigDecimal amount)
        implements Event {
}
